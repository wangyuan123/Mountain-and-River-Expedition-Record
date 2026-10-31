import com.wargame.WargameApplication;
import com.wargame.model.constants.*;
import com.wargame.model.dto.ForumDtos.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅连接独立 H2，每项记录真实服务的返回值，不访问游戏数据库。 */
public class GameplayAuditRound4 {
  @org.springframework.boot.test.context.SpringBootTest(classes=WargameApplication.class, properties={
    "spring.datasource.url=jdbc:h2:mem:gameplay_audit_round4;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create","spring.flyway.enabled=false","game.clock.enabled=false",
    "game.scheduling.enabled=false","game.bot.enabled=false","game.surrender-interval=3600000",
    "logging.level.root=ERROR","spring.main.banner-mode=off"})
  @org.springframework.test.context.ActiveProfiles("test") static class Harness {}
  static ConfigurableApplicationContext app;
  static TransactionTemplate tx;
  static final long NOW=1800000000000L;
  static final Map<String,Object> findings=new LinkedHashMap<>();
  static <T> T bean(Class<T> c){return app.getBean(c);}
  static Player player(String name,long at){
    Player p=new Player();p.setUsername(name);p.setPasswordHash("audit-only");p.setGameInitialized(true);
    p.setTax(0);p.setMorale(100);p.setLastTick(at);p.setCivilianPopulation(1000);p.setPrestige(100);
    p.setCityName(name);p.setCityPosX(10);p.setCityPosY(10);p=bean(PlayerRepository.class).saveAndFlush(p);
    stock(p.getId(),0,100000,100);return p;
  }
  static Resources stock(Long id,int slot,long gold,int diamond){
    Resources r=new Resources();r.setPlayerId(id);r.setCitySlot(slot);r.setGold(gold);r.setDiamond(diamond);
    r.setFood(10000);r.setSteel(100000);r.setOil(100000);r.setRare(100000);return bean(ResourcesRepository.class).saveAndFlush(r);
  }
  static Resources stock(Long id,int slot){return bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(id,slot).orElseThrow();}
  static PlayerCity city(Long id,int slot,long at){
    PlayerCity c=new PlayerCity();c.setOwnerId(id);c.setCitySlot(slot);c.setWorldId(1L);c.setName("审查城市"+slot);
    c.setX(10+slot*3);c.setY(10);c.setLastTick(at);return bean(PlayerCityRepository.class).saveAndFlush(c);
  }
  static Building building(Long id,String type,int level){
    Building b=new Building();b.setPlayerId(id);b.setType(type);b.setLevel(level);b.setSlot(0);
    return bean(BuildingRepository.class).saveAndFlush(b);
  }
  static Officer officer(Long id,int star,int mil,int def,int log,int know){
    Officer o=new Officer();o.setPlayerId(id);o.setName("审查军官");o.setStar(star);o.setLevel(1);
    o.setMilitary(mil);o.setDefense(def);o.setLogistics(log);o.setKnowledge(know);o.setSalary(0);o.setLoyalty(80);
    o.setExp(0L);o.setRole("idle");o.setSkills("[]");o.setRecruitAt(NOW);return bean(OfficerRepository.class).saveAndFlush(o);
  }
  static void item(Long id,String key,int count){bean(PlayerItemRepository.class).saveAndFlush(new PlayerItem(null,id,key,count,NOW));}
  static Officer fresh(Officer o){return bean(OfficerRepository.class).findById(o.getId()).orElseThrow();}
  static void probe(String key,java.util.function.Supplier<Object> action){
    try{findings.put(key,tx.execute(s->action.get()));}
    catch(Throwable e){findings.put(key+"_probe_error",e.toString());e.printStackTrace();}
    System.out.println("AUDIT_PROGRESS "+key+" recorded");
  }
  static Object at(long now,java.util.function.Supplier<Object> action){
    try(var clock=GameClock.use(()->now)){return action.get();}catch(Exception e){throw new RuntimeException(e);}
  }
  static ForumTopic topic(Long author){
    ForumTopic t=new ForumTopic();t.setBoardId(bean(ForumBoardRepository.class).findAll().get(0).getId());
    t.setAuthorId(author);t.setAuthorName("audit");t.setTitle("audit");t.setContent("audit");
    t.setCreatedAt(NOW);t.setUpdatedAt(NOW);return bean(ForumTopicRepository.class).saveAndFlush(t);
  }
  public static void main(String[] args)throws Exception{
    var manager=new org.springframework.test.context.TestContextManager(Harness.class);manager.beforeTestClass();manager.prepareTestInstance(new Harness());
    app=(ConfigurableApplicationContext)manager.getTestContext().getApplicationContext();
    tx=new TransactionTemplate(bean(PlatformTransactionManager.class));
    try(var clock=GameClock.use(()->NOW)){
      probe("branch_stage_gift_diamonds",()->{
        Player p=player("r4_branch_gift",NOW);city(p.getId(),0,NOW);city(p.getId(),1,NOW);stock(p.getId(),1,10000,0);item(p.getId(),"stage_gift_1",1);
        Map<String,Object> response;try(var scope=bean(CityScope.class).enter(p.getId(),1)){response=bean(DepotService.class).useItem(p.getId(),"stage_gift_1",null,null);}
        return Map.of("response",response,"mainDiamond",stock(p.getId(),0).getDiamond(),"branchDiamond",stock(p.getId(),1).getDiamond());
      });
      probe("stage_gift_diamond_overflow",()->{
        Player p=player("r4_gift_overflow",NOW);stock(p.getId(),0).setDiamond(Integer.MAX_VALUE-10);item(p.getId(),"stage_gift_1",1);
        var response=bean(DepotService.class).useItem(p.getId(),"stage_gift_1",null,null);
        return Map.of("response",response,"diamondAfter",stock(p.getId(),0).getDiamond());
      });
      probe("experience_book_two_entrances",()->{
        Player a=player("r4_exp_depot",NOW),b=player("r4_exp_officer",NOW);Officer oa=officer(a.getId(),1,40,40,40,40),ob=officer(b.getId(),1,40,40,40,40);
        item(a.getId(),"expBook",1);item(b.getId(),"expBook",1);
        var ra=bean(DepotService.class).useItem(a.getId(),"expBook",oa.getId(),null);var rb=bean(OfficerService.class).useExpBook(b.getId(),ob.getId(),"expBook",1);
        return Map.of("depot",ra,"officer",rb,"depotLevel",fresh(oa).getLevel(),"officerLevel",fresh(ob).getLevel());
      });
      probe("wash_changes_birth_attributes",()->{
        Player p=player("r4_wash",NOW);Officer o=officer(p.getId(),5,111,50,60,65);
        var response=bean(OfficerService.class).wash(p.getId(),o.getId());return Map.of("response",response,"before",List.of(111,50,60,65),"after",List.of(fresh(o).getMilitary(),fresh(o).getDefense(),fresh(o).getLogistics(),fresh(o).getKnowledge()));
      });
      probe("wash_changes_specialty_using_allocated_points",()->{
        Player p=player("r4_wash_spec",NOW);Officer o=officer(p.getId(),5,120,100,50,50);o.setLevel(30);o.setDefense(129);o.setAttrPoints(0);
        var response=bean(OfficerService.class).wash(p.getId(),o.getId());return Map.of("response",response,"originalMainAttribute","military","afterMilitary",fresh(o).getMilitary(),"afterDefense",fresh(o).getDefense());
      });
      probe("mayor_assignment_retroactive_steel",()->{
        Player a=player("r4_mayor_base",NOW-3600000),b=player("r4_mayor_change",NOW-3600000);building(a.getId(),"refinery",1);building(b.getId(),"refinery",1);
        stock(a.getId(),0).setSteel(0);stock(b.getId(),0).setSteel(0);Officer o=officer(b.getId(),1,40,40,100,40);
        bean(TickService.class).tick(a.getId());var response=bean(OfficerService.class).appoint(b.getId(),o.getId(),"mayor");bean(TickService.class).tick(b.getId());
        return Map.of("response",response,"baselineSteel",stock(a.getId(),0).getSteel(),"newMayorSteel",stock(b.getId(),0).getSteel());
      });
      probe("morale_tick_frequency",()->{
        Player a=player("r4_morale_hour",NOW-3600000),b=player("r4_morale_halves",NOW-3600000);a.setMorale(0);b.setMorale(0);
        bean(TickService.class).tick(a.getId());at(NOW-1800000,()->{bean(TickService.class).tick(b.getId());return true;});bean(TickService.class).tick(b.getId());
        return Map.of("oneHourMorale",bean(PlayerRepository.class).findById(a.getId()).orElseThrow().getMorale(),"twoHalfHoursMorale",bean(PlayerRepository.class).findById(b.getId()).orElseThrow().getMorale());
      });
      probe("food_cap_managed_unmanaged",()->{
        Player a=player("r4_food_unmanaged",NOW-3600000),b=player("r4_food_managed",NOW-3600000);
        for(Player p:List.of(a,b)){building(p.getId(),"farm",1);bean(ArmyUnitRepository.class).saveAndFlush(new ArmyUnit(null,p.getId(),"infantry",100));stock(p.getId(),0).setFood(bean(TickService.class).capacity(p.getId()).get("food"));}
        PlayerCity c=city(b.getId(),0,NOW-3600000);c.setGarrisonFoodAt(NOW-3600000);long cap=stock(a.getId(),0).getFood();
        bean(TickService.class).tick(a.getId());bean(TickService.class).tick(b.getId());return Map.of("initialCap",cap,"unmanagedFood",stock(a.getId(),0).getFood(),"managedFood",stock(b.getId(),0).getFood());
      });
      probe("tech_completed_before_tick_retroactive_production",()->{
        Player a=player("r4_tech_base",NOW-3600000),b=player("r4_tech_late",NOW-3600000);
        for(Player p:List.of(a,b)){building(p.getId(),"refinery",1);stock(p.getId(),0).setSteel(0);}
        TechResearchQueue q=new TechResearchQueue();q.setPlayerId(b.getId());q.setTechType("prod_steel");q.setTargetLevel(1);q.setStartedAt(NOW-3600000);q.setFinishesAt(NOW);q.setDurationSeconds(3600);
        bean(TechResearchQueueRepository.class).saveAndFlush(q);bean(TickService.class).tick(a.getId());bean(TechService.class).settleCompletedResearchForCity(b.getId(),0,NOW);bean(TickService.class).tick(b.getId());
        return Map.of("baselineSteel",stock(a.getId(),0).getSteel(),"lateTechSteel",stock(b.getId(),0).getSteel());
      });
      probe("building_event_lost_offline_production",()->{
        Player p=player("r4_offline_build",NOW-3600000);stock(p.getId(),0).setSteel(0);
        Construction q=new Construction();q.setPlayerId(p.getId());q.setBuildingType("refinery");q.setTargetLevel(1);q.setSlot(0);q.setStartAt(NOW-3600000);q.setFinishAt(NOW-1800000);
        bean(ConstructionRepository.class).saveAndFlush(q);bean(TickService.class).tick(p.getId());return Map.of("steelAfterHalfHourProducing",stock(p.getId(),0).getSteel(),"completedBuildings",bean(BuildingRepository.class).findByPlayerIdAndCitySlotAndType(p.getId(),0,"refinery").size());
      });
      probe("vip_amnesty_modifies_frozen_city",()->{
        Player p=player("r4_vip_frozen",NOW);p.setVipLevel(6);city(p.getId(),0,NOW);PlayerCity branch=city(p.getId(),1,NOW);
        branch.setMorale(0);branch.setResentment(80);branch.setSurrenderOfferId(123L);branch.setSurrenderFrozenAt(NOW-1);
        var response=bean(VipService.class).allCitiesAppease(p.getId(),"free_amnesty");return Map.of("response",response,"actualCities",2,"frozenMoraleAfter",branch.getMorale(),"frozenResentmentAfter",branch.getResentment());
      });
      probe("vip_amnesty_count",()->{Player p=player("r4_vip_count",NOW);p.setVipLevel(6);city(p.getId(),0,NOW);return Map.of("response",bean(VipService.class).allCitiesAppease(p.getId(),"free_amnesty"),"actualCities",1);});
      probe("guild_unilateral_friendship",()->{
        Player a=player("r4_guild_a",NOW),b=player("r4_guild_b",NOW);
        Long ga=((Number)bean(GuildService.class).create(a.getId(),"审查甲团","⚑").get("id")).longValue(),gb=((Number)bean(GuildService.class).create(b.getId(),"审查乙团","⚑").get("id")).longValue();
        bean(GuildRelationService.class).updateRelation(a.getId(),gb,"hostile");String before=bean(GuildRelationService.class).relationshipBetweenPlayers(a.getId(),b.getId());
        var response=bean(GuildRelationService.class).updateRelation(b.getId(),ga,"friendly");return Map.of("before",before,"after",bean(GuildRelationService.class).relationshipBetweenPlayers(a.getId(),b.getId()),"response",response,"otherSideConsent",false);
      });
      probe("forum_tip_total_overflow",()->{
        Player a=player("r4_tip_sender",NOW),b=player("r4_tip_author",NOW);stock(a.getId(),0).setGold(4000000000L);ForumTopic t=topic(b.getId());
        bean(ForumService.class).tipTopic(a.getId(),t.getId(),new TipRequest(1500000000,""));var response=bean(ForumService.class).tipTopic(a.getId(),t.getId(),new TipRequest(1500000000,""));
        return Map.of("response",response,"actualTransferred",3000000000L,"storedTotal",bean(ForumTopicRepository.class).findById(t.getId()).orElseThrow().getTipTotalGold());
      });
      probe("guild_application_survives_disband",()->{
        Player a=player("r4_disband_leader",NOW),b=player("r4_disband_applicant",NOW);Long g=((Number)bean(GuildService.class).create(a.getId(),"审查解散团","⚑").get("id")).longValue();
        bean(GuildService.class).apply(b.getId(),g);bean(GuildService.class).leave(a.getId());return Map.of("guildStillExists",bean(GuildRepository.class).existsById(g),"orphanApplications",bean(GuildApplicationRepository.class).findByGuildIdOrderByCreatedAtAsc(g).size());
      });
      probe("vip_gold_appease_ignores_cooldown",()->{
        Player p=player("r4_vip_repeat",NOW);p.setVipLevel(6);p.setMorale(20);p.setResentment(50);
        var first=bean(VipService.class).allCitiesAppease(p.getId(),"batch_gold");
        var second=bean(VipService.class).allCitiesAppease(p.getId(),"batch_gold");
        return Map.of("first",first,"second",second,"moraleAfter",p.getMorale(),"goldAfter",stock(p.getId(),0).getGold(),"sameGameTime",NOW);
      });
      probe("vip_gold_appease_spends_frozen_city",()->{
        Player p=player("r4_vip_frozen_gold",NOW);p.setVipLevel(6);city(p.getId(),0,NOW);PlayerCity c=city(p.getId(),1,NOW);
        stock(p.getId(),1,10000,0);c.setMorale(20);c.setCivilianPopulation(1000);c.setSurrenderOfferId(234L);c.setSurrenderFrozenAt(NOW-1);
        var response=bean(VipService.class).allCitiesAppease(p.getId(),"batch_gold");
        return Map.of("response",response,"frozenGoldBefore",10000,"frozenGoldAfter",stock(p.getId(),1).getGold(),"frozenMoraleAfter",c.getMorale());
      });
      probe("frozen_city_shield_cancel",()->{
        Player p=player("r4_frozen_shield",NOW);PlayerCity c=city(p.getId(),0,NOW);c.setSurrenderOfferId(345L);c.setSurrenderFrozenAt(NOW-1);
        CityState s=new CityState();s.setPlayerId(p.getId());s.setCitySlot(0);s.setShieldUntil(NOW+3600000);bean(CityStateRepository.class).saveAndFlush(s);
        var response=bean(CityService.class).deactivateShield(p.getId(),c.getId());
        return Map.of("response",response,"shieldBefore",NOW+3600000,"shieldAfter",s.getShieldUntil());
      });
      probe("stage_gift_extra_item_overflow",()->{
        Player p=player("r4_extra_item_overflow",NOW);item(p.getId(),"stage_gift_1",1);item(p.getId(),"speedUp10m",Integer.MAX_VALUE-1);
        var response=bean(DepotService.class).useItem(p.getId(),"stage_gift_1",null,null);
        return Map.of("response",response,"speedUp10m",bean(PlayerItemRepository.class).findByPlayerIdAndItemKey(p.getId(),"speedUp10m").orElseThrow().getCount());
      });
      probe("officer_exp_book_while_marching",()->{
        Player p=player("r4_march_exp",NOW);Officer o=officer(p.getId(),1,40,40,40,40);o.setRole("march");item(p.getId(),"expBookMax",1);
        var response=bean(DepotService.class).useItem(p.getId(),"expBookMax",o.getId(),null);
        return Map.of("response",response,"levelAfter",fresh(o).getLevel(),"role",fresh(o).getRole());
      });
      concurrentTips();
      probe("normal_appease_ignores_cooldown",()->{
        Player p=player("r4_normal_appease",NOW);p.setMorale(20);
        var first=bean(GameStateService.class).appease(p.getId(),"gold");var second=bean(GameStateService.class).appease(p.getId(),"gold");
        return Map.of("first",first,"second",second,"moraleAfter",p.getMorale(),"goldAfter",stock(p.getId(),0).getGold());
      });
      probe("war_declaration_overwrites_existing_war",()->{
        Player a=player("r4_war_a",NOW),b=player("r4_war_b",NOW),c=player("r4_war_c",NOW);
        for(Player p:List.of(a,b,c))p.setNewbieProtectionStartedAt(0L);
        PlayerCity cb=city(b.getId(),0,NOW),cc=city(c.getId(),0,NOW);
        var first=bean(WorldService.class).declareWar(a.getId(),cb.getId(),true);var second=bean(WorldService.class).declareWar(c.getId(),cb.getId(),true);
        Map<String,Object> evidence=new LinkedHashMap<>();
        evidence.put("first",first);evidence.put("second",second);
        evidence.put("aId",a.getId());evidence.put("bId",b.getId());evidence.put("cId",c.getId());
        evidence.put("aOpponent",a.getWarAgainstId());evidence.put("bOpponent",b.getWarAgainstId());evidence.put("cOpponent",c.getWarAgainstId());
        return evidence;
      });
      probe("shield_expiry_loses_past_bonus",()->{
        Player a=player("r4_shield_interval",NOW-3600000),b=player("r4_shield_exact",NOW-3600000);
        for(Player p:List.of(a,b)){building(p.getId(),"refinery",1);stock(p.getId(),0).setSteel(0);
          CityState s=new CityState();s.setPlayerId(p.getId());s.setShieldUntil(NOW-1800000);bean(CityStateRepository.class).saveAndFlush(s);}
        bean(TickService.class).tick(a.getId());at(NOW-1800001,()->{bean(TickService.class).tick(b.getId());return true;});bean(TickService.class).tick(b.getId());
        return Map.of("oneLateTickSteel",stock(a.getId(),0).getSteel(),"tickBeforeExpirySteel",stock(b.getId(),0).getSteel());
      });
      probe("deleted_display_name_not_anonymized",()->{
        Player p=player("r4_privacy_login",NOW);p.setDisplayName("审查统帅旧名");p.setAccountStatus("DELETED");bean(PlayerRepository.class).saveAndFlush(p);
        Map<String,Object> report=new LinkedHashMap<>(Map.of("attackerName",p.getDisplayName(),"defenderName","对手"));
        bean(ReportPrivacyService.class).anonymize(List.of(report));return Map.of("reportAfter",report,"username",p.getUsername());
      });
      probe("deleted_seller_order_swallows_gold",()->{
        Player a=player("r4_deleted_seller",NOW),b=player("r4_order_buyer",NOW);building(a.getId(),"exchange",1);building(b.getId(),"exchange",1);
        var order=bean(MarketService.class).createOrder(a.getId(),0,"steel",100,1);
        a.setAccountStatus("PENDING_DELETION");a.setRecoverUntil(System.currentTimeMillis()-1);a.setDisabled(1);bean(PlayerRepository.class).saveAndFlush(a);
        bean(AccountService.class).purgeExpiredAccount(a.getId());
        String before=bean(MarketOrderRepository.class).findById(order.getId()).orElseThrow().getStatus();
        var result=bean(MarketService.class).buyOrder(b.getId(),0,order.getId());
        return Map.of("sellerStatus",bean(PlayerRepository.class).findById(a.getId()).orElseThrow().getAccountStatus(),"orderAfterPurge",before,"orderAfterPurchase",result.getStatus(),"buyerGold",stock(b.getId(),0).getGold(),"buyerSteel",stock(b.getId(),0).getSteel(),"sellerWalletExists",bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(a.getId(),0).isPresent());
      });
      System.out.println("AUDIT_JSON "+JsonUtil.toJson(findings));
    }finally{app.close();}
  }
  /** 两个真实服务事务同步读取同一钱包，固定可发生的并发交错，不改服务实现。 */
  static void concurrentTips()throws Exception{
    Long[] ids=tx.execute(s->{Player p=player("r4_concurrent_tip",NOW),a=player("r4_tip_a",NOW),b=player("r4_tip_b",NOW);
      stock(p.getId(),0).setGold(100L);stock(a.getId(),0).setGold(0L);stock(b.getId(),0).setGold(0L);
      return new Long[]{p.getId(),a.getId(),b.getId(),topic(a.getId()).getId(),topic(b.getId()).getId()};});
    var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
    try{
      List<Future<Object>> work=new ArrayList<>();
      for(int i=0;i<2;i++){final int j=i;work.add(pool.submit(()->tx.execute(s->{stock(ids[0],0);
        try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
        return bean(ForumService.class).tipTopic(ids[0],ids[3+j],new TipRequest(60,"audit"));})));}
      List<Object> responses=new ArrayList<>();for(var f:work)try{responses.add(f.get(20,TimeUnit.SECONDS));}catch(Exception e){responses.add(e.toString());}
      findings.put("forum_concurrent_double_spending",tx.execute(s->Map.of("responses",responses,"senderGold",stock(ids[0],0).getGold(),"firstAuthorGold",stock(ids[1],0).getGold(),"secondAuthorGold",stock(ids[2],0).getGold(),"initialTotalGold",100)));
    }finally{pool.shutdownNow();}
  }
}
