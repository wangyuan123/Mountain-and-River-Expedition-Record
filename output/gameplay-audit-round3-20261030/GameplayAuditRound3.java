import com.wargame.WargameApplication;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import java.util.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只连接独立 H2 内存库，调用真实服务记录现象；不修改游戏规则。 */
public class GameplayAuditRound3 {
  @org.springframework.boot.test.context.SpringBootTest(classes=WargameApplication.class, properties={
    "spring.datasource.url=jdbc:h2:mem:gameplay_audit_round3;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create","spring.flyway.enabled=false","game.clock.enabled=false",
    "game.scheduling.enabled=false","game.bot.enabled=false","game.surrender-interval=3600000",
    "logging.level.root=ERROR","spring.main.banner-mode=off"})
  @org.springframework.test.context.ActiveProfiles("test")
  static class Harness {}
  static ConfigurableApplicationContext app;
  static TransactionTemplate tx;
  static final Map<String,Object> findings = new LinkedHashMap<>();
  static <T> T bean(Class<T> c) { return app.getBean(c); }
  static Player player(String name, long at) {
    Player p = new Player(); p.setUsername(name); p.setPasswordHash("audit-only");
    p.setGameInitialized(true); p.setTax(0); p.setMorale(100); p.setLastTick(at);
    p.setCivilianPopulation(1000); p.setCityPosX(10); p.setCityPosY(10); p.setPrestige(0);
    p = bean(PlayerRepository.class).save(p);
    Resources r = new Resources(); r.setPlayerId(p.getId()); r.setFood(10000); r.setSteel(1000000);
    r.setOil(1000000); r.setRare(1000000); r.setGold(100000); r.setDiamond(0);
    bean(ResourcesRepository.class).save(r); return p;
  }
  static Building building(Long p, String type, int level) {
    Building b = new Building(); b.setPlayerId(p); b.setType(type); b.setLevel(level);
    return bean(BuildingRepository.class).save(b);
  }
  static PlayerCity city(Long p, long at) {
    PlayerCity c = new PlayerCity(); c.setWorldId(1L); c.setOwnerId(p); c.setCitySlot(0);
    c.setName("audit city"); c.setX(10); c.setY(10); c.setLastTick(at);
    c.setGarrisonFoodAt(at); return bean(PlayerCityRepository.class).save(c);
  }
  static void probe(String key, java.util.function.Supplier<Object> test) {
    try { findings.put(key, tx.execute(s -> test.get())); }
    catch (Throwable e) { findings.put(key+"_probe_error",e.toString()); e.printStackTrace(); }
    System.out.println("AUDIT_PROGRESS "+key+" "+findings.get(key));
  }
  public static void main(String[] args) throws Exception {
    var manager = new org.springframework.test.context.TestContextManager(Harness.class);
    manager.beforeTestClass(); manager.prepareTestInstance(new Harness());
    app = (ConfigurableApplicationContext)manager.getTestContext().getApplicationContext();
    tx = new TransactionTemplate(bean(PlatformTransactionManager.class));
    long now = 1800000000000L;
    try (var clock = GameClock.use(() -> now)) {
      probe("mail_attachment_integer_overflow", () -> {
        Player sender = player("round3_mail_sender",now), receiver = player("round3_mail_receiver",now);
        var response = bean(MailService.class).send(sender.getId(),receiver.getUsername(),"audit","",List.of(
          Map.of("type","diamond","qty",1000000000),Map.of("type","diamond","qty",1000000000),Map.of("type","diamond","qty",1000000000)));
        return Map.of("success",response.get("success"),"diamondsBefore",0,"diamondsAfter",bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(sender.getId(),0).orElseThrow().getDiamond(),"attachmentEntries",3,"quantityPerEntry",1000000000);
      });
      probe("prisoner_recruitment_retroactive_food", () -> {
        Player p=player("round3_prisoner",now-3600000); PlayerCity c=city(p.getId(),now-3600000);
        building(p.getId(),"factory",1); building(p.getId(),"prisoner_camp",1);
        PrisonerUnit w=new PrisonerUnit();w.setPlayerId(p.getId());w.setCityId(c.getId());w.setSettlementId(1L);
        w.setSourceId("audit");w.setOriginalOwnerId(p.getId());w.setType("infantry");w.setOriginalCount(100);w.setCount(100);w.setCreatedAt(now-3600000);
        bean(PrisonerUnitRepository.class).save(w);
        var response=bean(PrisonerService.class).operateInCity(p.getId(),c.getId(),"recruit","infantry",100,"round3_prisoners");
        bean(GarrisonFoodService.class).settle(c,now);
        return Map.of("success",response.get("success"),"foodBefore",10000,"foodAfter",bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getFood());
      });
      probe("mail_army_retroactive_food", () -> {
        Player p=player("round3_mail_army",now-3600000); PlayerCity c=city(p.getId(),now-3600000);building(p.getId(),"factory",1);
        Mail mail=bean(MailService.class).sendSystem(p.getId(),"系统","system","audit","",List.of(Map.of("type","infantry","qty",100)));
        var response=bean(MailService.class).claimAttach(p.getId(),mail.getId());bean(GarrisonFoodService.class).settle(c,now);
        return Map.of("success",response.get("success"),"foodBefore",10000,"foodAfter",bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getFood());
      });
      probe("wall_downgrade_keeps_overcapacity_forts", () -> {
        Player p=player("round3_wall",now);PlayerCity c=city(p.getId(),now);building(p.getId(),"wall",1);
        var build=bean(FortService.class).build(p.getId(),"bunker",3000);
        var dismantle=bean(BuildService.class).dismantle(p.getId(),"wall",0);
        var job=bean(ConstructionRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).stream().filter(q->"wall".equals(q.getBuildingType())).findFirst().orElseThrow();
        bean(BuildService.class).completeUpgrade(p.getId(),job.getFinishAt());
        return Map.of("build",build,"dismantle",dismantle,"capacityAfter",bean(FortService.class).fortCap(p.getId()),"fortsAfter",bean(FortService.class).totalForts(p.getId()),"combatForts",bean(MarchTargetService.class).getTargetForts(c));
      });
      probe("cancel_production_leaves_idle_lane", () -> {
        Player p=player("round3_queue",now);building(p.getId(),"factory",1);building(p.getId(),"house",1);
        var service=bean(ArmyService.class);var first=service.recruit(p.getId(),"infantry",10);var second=service.recruit(p.getId(),"infantry",10);
        Long firstId=((Number)first.get("queueId")).longValue(), secondId=((Number)second.get("queueId")).longValue();
        var repo=bean(ArmyProductionQueueRepository.class);long before=repo.findById(secondId).orElseThrow().getStartedAt();
        boolean cancelled=service.cancelProduction(p.getId(),firstId);var remaining=repo.findById(secondId).orElseThrow();
        return Map.of("cancelled",cancelled,"now",now,"secondStartsBefore",before,"secondStartsAfter",remaining.getStartedAt(),"idleSeconds",(remaining.getStartedAt()-now)/1000,"remainingOrders",repo.findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(p.getId(),0).size());
      });
      probe("construction_refund_changes_with_technology", () -> {
        Player p=player("round3_refund",now);building(p.getId(),"command",2);building(p.getId(),"farm",1);
        building(p.getId(),"lab",2);building(p.getId(),"refinery",1);building(p.getId(),"exchange",1);building(p.getId(),"depot",1);
        var research=bean(TechService.class).startResearch(p.getId(),"log_build");
        if (!Boolean.TRUE.equals(research.get("success"))) throw new AssertionError(research);
        long finishedAt=((Number)research.get("finishesAt")).longValue();
        var balance=bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow();long paid;
        Map<String,Object> start;long opening=balance.getSteel();
        // 科技还差15秒时开工民居；科技完成时民居仍在施工，退款应保持开工实扣口径。
        try(var at=GameClock.use(()->finishedAt-15000)) { start=bean(BuildService.class).upgrade(p.getId(),"house",0); }
        catch(Exception e) { throw new RuntimeException(e); }
        paid=opening-balance.getSteel();
        try(var at=GameClock.use(()->finishedAt)) { bean(TechService.class).settleCompletedResearchForCity(p.getId(),0,finishedAt); }
        catch(Exception e) { throw new RuntimeException(e); }
        long before=balance.getSteel();Map<String,Object> cancel;
        try(var at=GameClock.use(()->finishedAt)) { cancel=bean(BuildService.class).cancel(p.getId(),"house",0); }
        catch(Exception e) { throw new RuntimeException(e); }
        return Map.of("started",start.get("success"),"start",start,"cancelled",cancel.get("success"),"paidSteel",paid,"expectedHalfRefund",paid/2,"actualSteelRefund",balance.getSteel()-before);
      });
      probe("new_officer_retroactive_salary", () -> {
        Player p=player("round3_salary",now-3600000);building(p.getId(),"academy",1);
        Academy a=new Academy();a.setPlayerId(p.getId());a.setOfficers(JsonUtil.toJson(List.of(Map.of("name","audit officer","star",1,"salary",100,"loyalty",80))));bean(AcademyRepository.class).save(a);
        var recruit=bean(OfficerService.class).recruit(p.getId(),0);long afterRecruit=bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getGold();
        bean(TickService.class).tick(p.getId());long afterTick=bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getGold();
        return Map.of("recruited",recruit.get("success"),"goldAfterRecruit",afterRecruit,"goldAfterSameTimeTick",afterTick,"retroactiveSalary",afterRecruit-afterTick);
      });
      probe("new_officer_immediate_arrears_penalty", () -> {
        Player p=player("round3_salary_empty",now-3600000);building(p.getId(),"academy",1);
        bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().setGold(80);
        Academy a=new Academy();a.setPlayerId(p.getId());a.setOfficers(JsonUtil.toJson(List.of(Map.of("name","new officer","star",1,"salary",100,"loyalty",80))));bean(AcademyRepository.class).save(a);
        var response=bean(OfficerService.class).recruit(p.getId(),0);long officerId=((Number)response.get("officerId")).longValue();
        bean(TickService.class).tick(p.getId());var r=bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow();
        var officer=bean(OfficerRepository.class).findById(officerId);
        return Map.of("recruited",response.get("success"),"recruitedAt",now,"loyaltyBefore",80,"loyaltyAfter",officer.map(Officer::getLoyalty).orElse(-1),"officerStillPresent",officer.isPresent(),"arrearsStartedAt",r.getSalaryArrearsStartedAt()==null?-1:r.getSalaryArrearsStartedAt());
      });
      probe("tax_change_retroactive_income", () -> {
        Player baseline=player("round3_tax_baseline",now-3600000);building(baseline.getId(),"house",1);
        bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(baseline.getId(),0).orElseThrow().setGold(0);bean(TickService.class).tick(baseline.getId());
        long baselineGold=bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(baseline.getId(),0).orElseThrow().getGold();
        Player p=player("round3_tax",now-3600000);building(p.getId(),"house",1);
        var r=bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow();r.setGold(0);
        var response=bean(GameStateService.class).setTax(p.getId(),100);bean(TickService.class).tick(p.getId());
        return Map.of("oldTax",0,"newTax",100,"changedAt",now,"previousTick",now-3600000,"success",response.get("success"),"baselineGoldWithoutChange",baselineGold,"goldAfterSameTimeTick",bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getGold());
      });
      System.out.println("AUDIT_JSON "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(findings));
    } finally { app.close(); }
  }
}
