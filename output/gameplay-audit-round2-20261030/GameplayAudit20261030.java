import com.wargame.WargameApplication;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.service.quest.*;
import com.wargame.util.GameClock;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;

public class GameplayAudit20261030 {
  @org.springframework.boot.test.context.SpringBootTest(classes=WargameApplication.class, properties={
    "spring.datasource.url=jdbc:h2:mem:gameplay_audit_20261030;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
  static Player player(String name) {
    Player p = new Player(); p.setUsername(name); p.setPasswordHash("audit-only");
    p.setGameInitialized(true); p.setTax(0); p.setMorale(100); p.setLastTick(GameClock.now());
    p.setCivilianPopulation(100); p.setCityPosX(10); p.setCityPosY(10); p.setPrestige(0);
    p = bean(PlayerRepository.class).save(p);
    Resources r = new Resources(); r.setPlayerId(p.getId()); r.setFood(100000); r.setSteel(100000);
    r.setOil(100000); r.setRare(100000); r.setGold(100000); r.setDiamond(0);
    bean(ResourcesRepository.class).save(r); return p;
  }
  static Building building(Long p, int slot, String type, int level) {
    Building b = new Building(); b.setPlayerId(p); b.setCitySlot(slot); b.setType(type); b.setLevel(level);
    return bean(BuildingRepository.class).save(b);
  }
  static PlayerCity city(Long p, int slot, long at) {
    PlayerCity c = new PlayerCity(); c.setWorldId(1L); c.setOwnerId(p); c.setCitySlot(slot);
    c.setName("audit city"); c.setX(10+slot); c.setY(10); c.setLastTick(at);
    c.setGarrisonFoodAt(at); return bean(PlayerCityRepository.class).save(c);
  }
  static void run(String name, Runnable probe) {
    try { probe.run(); } catch (Throwable e) { findings.put(name+"_probe_error", e.toString()); }
    System.out.println("AUDIT_PROGRESS "+name+" "+findings.get(name));
  }
  public static void main(String[] args) throws Exception {
    var manager=new org.springframework.test.context.TestContextManager(Harness.class);
    manager.beforeTestClass();manager.prepareTestInstance(new Harness());
    app=(ConfigurableApplicationContext)manager.getTestContext().getApplicationContext();
    tx = new TransactionTemplate(bean(PlatformTransactionManager.class));
    try {
      run("impossible_building_quests", () -> tx.execute(s -> {
        Player p = player("audit_quest_caps"); building(p.getId(),0,"command",10);
        var response=bean(BuildService.class).upgrade(p.getId(),"command",0);
        var impossible=QuestCatalog.CHAPTERS.stream().flatMap(c->c.quests().stream())
          .filter(q->q.eventType().equals("BUILD_UPGRADE_DONE")&&q.targetValue()>10)
          .map(q->q.id()+":"+q.targetKey()+">="+q.targetValue()).toList();
        findings.put("impossible_building_quests",Map.of("upgradeAt10",response,"unreachable",impossible)); return null;
      }));
      run("locked_chapter_claim", () -> tx.execute(s -> {
        Player p=player("audit_locked_claim"); var q=bean(QuestService.class);
        q.onEvent(p.getId(),"BUILD_UPGRADE_DONE","oilfield",3);
        var view=q.getPlayerQuests(p.getId()).stream().filter(c->c.get("id").equals("stage2")).findFirst().orElseThrow();
        var claim=q.claim(p.getId(),"q2_1");
        findings.put("locked_chapter_claim",Map.of("displayedChapterStatus",view.get("status"),"claim",claim)); return null;
      }));
      run("building_level_counts_operations", () -> tx.execute(s -> {
        Player p=player("audit_two_houses");
        for(int slot=0;slot<2;slot++) {
          Construction c=new Construction(); c.setPlayerId(p.getId()); c.setBuildingType("house");
          c.setTargetLevel(1); c.setSlot(slot); c.setStartAt(0L); c.setFinishAt(GameClock.now());
          bean(ConstructionRepository.class).save(c); bean(BuildService.class).completeUpgrade(p.getId(),GameClock.now());
        }
        var pq=bean(PlayerQuestRepository.class).findByPlayerIdAndQuestId(p.getId(),"q1_1").orElseThrow();
        findings.put("building_level_counts_operations",Map.of("houseLevels",bean(BuildingRepository.class)
          .findByPlayerIdAndType(p.getId(),"house").stream().map(Building::getLevel).toList(),
          "questProgress",pq.getProgress(),"questStatus",pq.getStatus())); return null;
      }));
      run("surrender_scheduler_transaction", () -> {
        Long id=tx.execute(s->{ Player a=player("audit_offer_a"), b=player("audit_offer_b");
          CitySurrenderOffer o=new CitySurrenderOffer(); o.setWorldId(1L); o.setInitiatorId(a.getId());
          o.setDefenderId(b.getId()); o.setCityId(987654L); o.setCityName("audit"); o.setCityX(1); o.setCityY(1);
          o.setStatus("PENDING"); o.setSentAt(0L); o.setResponseExpiresAt(GameClock.now()-1);
          return bean(CitySurrenderOfferRepository.class).save(o).getId(); });
        Long[] frozen=tx.execute(s->{Player a=player("audit_accepted_a"),b=player("audit_accepted_b");
          long now=GameClock.now();PlayerCity c=city(b.getId(),1,now);
          CitySurrenderOffer o=new CitySurrenderOffer();o.setWorldId(1L);o.setInitiatorId(a.getId());
          o.setDefenderId(b.getId());o.setCityId(c.getId());o.setCityName("audit frozen");o.setCityX(11);o.setCityY(10);
          o.setStatus("ACCEPTED");o.setSentAt(now-172800000L);o.setResponseExpiresAt(now-86400000L);
          o.setAcceptedAt(now-86400000L);o.setTransferDeadlineAt(now-1);bean(CitySurrenderOfferRepository.class).saveAndFlush(o);
          c.setSurrenderOfferId(o.getId());c.setSurrenderRecipientId(a.getId());c.setSurrenderFrozenAt(now-86400000L);
          c.setSurrenderTransferDeadlineAt(now-1);return new Long[]{o.getId(),c.getId()};});
        bean(CitySurrenderService.class).settleDue();
        String afterScheduler=bean(CitySurrenderOfferRepository.class).findById(id).orElseThrow().getStatus();
        String directError; try { bean(CitySurrenderOfferRepository.class).findForUpdate(id); directError="none"; }
          catch(Exception e){directError=e.getClass().getSimpleName()+":"+e.getMessage();}
        bean(CitySurrenderService.class).expire(id,GameClock.now());
        String afterProxy=bean(CitySurrenderOfferRepository.class).findById(id).orElseThrow().getStatus();
        findings.put("surrender_scheduler_transaction",Map.of("afterScheduler",afterScheduler,"afterTransactionalProxy",afterProxy,"lockWithoutTransaction",directError,
          "acceptedAfterScheduler",bean(CitySurrenderOfferRepository.class).findById(frozen[0]).orElseThrow().getStatus(),
          "overdueCityStillFrozen",bean(PlayerCityRepository.class).findById(frozen[1]).orElseThrow().isSurrenderFrozen(GameClock.now())));
      });
      run("market_cross_city_slot_race", () -> {
        Long p=tx.execute(s->{Player a=player("audit_market_slots");
          building(a.getId(),0,"exchange",1); building(a.getId(),1,"exchange",1);
          Resources r=new Resources();r.setPlayerId(a.getId());r.setCitySlot(1);r.setFood(100000);r.setGold(0);
          bean(ResourcesRepository.class).save(r);
          bean(MarketService.class).createOrder(a.getId(),0,"food",1,1);
          bean(MarketService.class).createOrder(a.getId(),0,"food",1,1); return a.getId();});
        CyclicBarrier barrier=new CyclicBarrier(2); ExecutorService pool=Executors.newFixedThreadPool(2);
        try { List<Future<Object>> futures=new ArrayList<>();
          for(int i=0;i<2;i++){final int slot=i; futures.add(pool.submit(()->tx.execute(s->{
            bean(ResourcesRepository.class).findCityForTreatment(p,slot).orElseThrow();
            try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
            return bean(MarketService.class).createOrder(p,slot,"food",1,1).getId();})));}
          List<Object> results=new ArrayList<>();for(var f:futures)try{results.add(f.get(20,TimeUnit.SECONDS));}catch(Exception e){results.add(e.toString());}
          findings.put("market_cross_city_slot_race",Map.of("cap",3,"activeOrders",bean(MarketOrderRepository.class).countBySellerIdAndStatus(p,"ACTIVE"),"requests",results));
        }finally{pool.shutdownNow();}
      });
      run("healing_retroactive_food", () -> tx.execute(s->{
        long now=GameClock.now(),from=now-3600000; Player p=player("audit_healing_food"); p.setLastTick(from);
        PlayerCity c=city(p.getId(),0,from);
        Resources r=bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow();r.setFood(10000);
        WoundedUnit w=new WoundedUnit();w.setPlayerId(p.getId());w.setCitySlot(0);w.setCityId(c.getId());
        w.setCityX(10);w.setCityY(10);w.setCityName("audit");w.setType("infantry");w.setCount(100);
        w.setCreatedAt(from);w.setExpiresAt(now+3600000);w.setRecoveryPercent(50);bean(WoundedUnitRepository.class).save(w);
        bean(WoundedService.class).heal(p.getId(),w.getId(),100,"gold");
        bean(GarrisonFoodService.class).settle(c,now);
        findings.put("healing_retroactive_food",Map.of("foodBefore",10000,"foodAfter",r.getFood(),"healedNow",100,"elapsedBeforeHealingHours",1)); return null;
      }));
      run("tax_salary_tick_frequency", () -> {
        long from=GameClock.now();long[] ids=tx.execute(s->{long[] a=new long[2];for(int i=0;i<2;i++){
          Player p=player("audit_salary_"+i);p.setTax(50);p.setMorale(50);p.setLastTick(from);p.setCivilianPopulation(6000);
          building(p.getId(),0,"house",5);Resources r=bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow();r.setGold(20000000);
          Officer o=new Officer();o.setPlayerId(p.getId());o.setName("audit");o.setRole("idle");o.setSalary(100);o.setLoyalty(80);bean(OfficerRepository.class).save(o);a[i]=p.getId();}return a;});
        try(var clock=GameClock.use(()->from+3600000)){bean(TickService.class).tick(ids[0]);}catch(Exception e){throw new RuntimeException(e);}
        for(int i=1;i<=2;i++){final long time=from+i*1800000L;try(var clock=GameClock.use(()->time)){bean(TickService.class).tick(ids[1]);}catch(Exception e){throw new RuntimeException(e);}}
        findings.put("tax_salary_tick_frequency",Map.of("oneHourlySettlement",bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(ids[0],0).orElseThrow().getGold(),
          "twoHalfHourlySettlements",bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(ids[1],0).orElseThrow().getGold()));
      });
      System.out.println("AUDIT_JSON "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(findings));
    } finally { app.close(); }
  }
}
