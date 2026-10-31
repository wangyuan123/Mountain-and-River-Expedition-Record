import com.wargame.WargameApplication;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import java.util.*;
import org.mockito.Mockito;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 独立内存库中的合成城市，仅检查正常经营、完工与时间结算，不启动网络服务。 */
public class GameplayAuditRound5 {
    @org.springframework.boot.test.context.SpringBootTest(classes = WargameApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:gameplay_audit_round5;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false", "game.clock.enabled=false",
        "game.scheduling.enabled=false", "game.bot.enabled=false", "logging.level.root=ERROR", "spring.main.banner-mode=off"
    })
    @org.springframework.test.context.ActiveProfiles("test")
    static class Harness {
        @org.springframework.boot.test.mock.mockito.MockBean WebSocketPushService pushes;
    }
    static ConfigurableApplicationContext app;
    static TransactionTemplate tx;
    static final long NOW = 1800000000000L;
    static final Map<String, Object> observations = new LinkedHashMap<>();
    static <T> T bean(Class<T> type) { return app.getBean(type); }
    static Player player(String name, long at) {
        Player p = new Player();
        p.setUsername(name); p.setPasswordHash("isolated-fixture"); p.setGameInitialized(true);
        p.setTax(0); p.setMorale(100); p.setLastTick(at); p.setCivilianPopulation(0); p.setPrestige(100);
        p.setCityName(name); p.setCityPosX(10); p.setCityPosY(10);
        p = bean(PlayerRepository.class).saveAndFlush(p);
        Resources r = new Resources(); r.setPlayerId(p.getId()); r.setGold(100000);
        r.setFood(10000); r.setSteel(100000); r.setOil(100000); r.setRare(100000);
        bean(ResourcesRepository.class).saveAndFlush(r);
        return p;
    }
    static Resources stock(Player p) { return bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(), 0).orElseThrow(); }
    static Building building(Player p, String type, int level) {
        Building b = new Building(); b.setPlayerId(p.getId()); b.setType(type); b.setLevel(level); b.setSlot(0);
        return bean(BuildingRepository.class).saveAndFlush(b);
    }
    static PlayerCity city(Player p) {
        PlayerCity c = new PlayerCity(); c.setOwnerId(p.getId()); c.setCitySlot(0); c.setWorldId(1L);
        c.setName(p.getCityName()); c.setX(10); c.setY(10); c.setLastTick(p.getLastTick()); c.setReadyAt(0L);
        return bean(PlayerCityRepository.class).saveAndFlush(c);
    }
    static Officer salaried(Player p, long at) {
        Officer o = new Officer(); o.setPlayerId(p.getId()); o.setName("合成军官"); o.setStar(1); o.setLevel(1);
        o.setMilitary(40); o.setDefense(40); o.setLogistics(40); o.setKnowledge(40);
        o.setSalary(100); o.setLoyalty(80); o.setRole("idle"); o.setSkills("[]"); o.setRecruitAt(at);
        return bean(OfficerRepository.class).saveAndFlush(o);
    }
    static void item(Player p, String key) { bean(PlayerItemRepository.class).saveAndFlush(new PlayerItem(null, p.getId(), key, 1, NOW)); }
    static Object at(long now, java.util.function.Supplier<Object> work) {
        try (var clock = GameClock.use(() -> now)) { return work.get(); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    static void settle(Player p, long now) { at(now, () -> { bean(TickService.class).settleEconomyBeforeChange(p.getId()); return true; }); }
    static void observe(String key, java.util.function.Supplier<Object> work) {
        try { observations.put(key, tx.execute(status -> { status.setRollbackOnly(); return work.get(); })); }
        catch (Throwable error) { observations.put(key + "_probe_error", error.toString()); error.printStackTrace(); }
        System.out.println("AUDIT_PROGRESS " + key);
    }
    public static void main(String[] args) throws Exception {
        var manager = new org.springframework.test.context.TestContextManager(Harness.class);
        manager.beforeTestClass(); manager.prepareTestInstance(new Harness());
        app = (ConfigurableApplicationContext) manager.getTestContext().getApplicationContext();
        tx = new TransactionTemplate(bean(PlatformTransactionManager.class));
        bean(org.springframework.jdbc.core.JdbcTemplate.class).execute("CREATE TABLE IF NOT EXISTS resource_overflow_state (resource_id BIGINT NOT NULL, resource_type VARCHAR(16) NOT NULL, stage TINYINT NOT NULL, last_period BIGINT NOT NULL, PRIMARY KEY (resource_id, resource_type))");
        try (var clock = GameClock.use(() -> NOW)) {
            observe("scheduled_completion_notification", () -> {
                Player p = player("r5-notifications", NOW - 3600000);
                Construction c = new Construction(); c.setPlayerId(p.getId()); c.setBuildingType("refinery");
                c.setSlot(0); c.setTargetLevel(1); c.setStartAt(NOW - 3600000); c.setFinishAt(NOW);
                bean(ConstructionRepository.class).saveAndFlush(c);
                TechResearchQueue q = new TechResearchQueue(); q.setPlayerId(p.getId()); q.setTechType("prod_steel");
                q.setTargetLevel(1); q.setStartedAt(NOW - 3600000); q.setFinishesAt(NOW); q.setDurationSeconds(3600);
                bean(TechResearchQueueRepository.class).saveAndFlush(q);
                Mockito.clearInvocations(bean(WebSocketPushService.class));
                bean(TickService.class).tick(p.getId());
                List<Object> sent = Mockito.mockingDetails(bean(WebSocketPushService.class)).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("pushTickUpdate"))
                    .map(i -> {
                        Map<?, ?> data = (Map<?, ?>) i.getArguments()[1];
                        return (Object) Map.of("containsCompletedBuilds", data.containsKey("completedBuilds"),
                            "containsCompletedResearch", data.containsKey("completedResearch"),
                            "containsTech", data.containsKey("tech"), "containsCityState", data.containsKey("cityState"));
                    }).toList();
                return Map.of("buildingLevel", bean(TickService.class).buildingLevel(p.getId(), "refinery"),
                    "technologyLevel", bean(TechService.class).getTechMap(p.getId()).get("prod_steel"), "tickPayloads", sent);
            });
            observe("population_tax_settlement_frequency", () -> {
                Player a = player("r5-hour", NOW - 3600000), b = player("r5-minute", NOW - 3600000);
                for (Player p : List.of(a, b)) { p.setTax(30); p.setMorale(70); stock(p).setGold(0); building(p, "house", 5); }
                settle(a, NOW);
                for (int minute = 1; minute <= 60; minute++) settle(b, NOW - 3600000 + minute * 60000L);
                return Map.of("hourPopulation", a.getCivilianPopulation(), "minutePopulation", b.getCivilianPopulation(),
                    "hourGold", stock(a).getGold(), "minuteGold", stock(b).getGold());
            });
            observe("managed_food_offline_window", () -> {
                Player a = player("r5-food-standard", NOW - 86400000), b = player("r5-food-managed", NOW - 86400000);
                city(a); PlayerCity managed = city(b); managed.setGarrisonFoodAt(NOW - 86400000);
                bean(PlayerCityRepository.class).saveAndFlush(managed);
                for (Player p : List.of(a, b)) bean(ArmyUnitRepository.class).saveAndFlush(new ArmyUnit(null, p.getId(), "infantry", 100));
                settle(a, NOW); settle(b, NOW);
                return Map.of("initialFood", 10000, "standardFood", stock(a).getFood(), "managedFood", stock(b).getFood(),
                    "offlineHours", 24, "configuredWindowHours", 8);
            });
            observe("resource_receipt_economic_boundary", () -> {
                Player a = player("r5-receipt-settled", NOW - 3600000), b = player("r5-receipt-pending", NOW - 3600000);
                Officer oa = salaried(a, NOW - 7200000), ob = salaried(b, NOW - 7200000);
                for (Player p : List.of(a, b)) { stock(p).setGold(0); item(p, "goldBox"); }
                settle(a, NOW);
                var ra = bean(DepotService.class).useItem(a.getId(), "goldBox", null, null);
                var rb = bean(DepotService.class).useItem(b.getId(), "goldBox", null, null);
                settle(b, NOW);
                return Map.of("settledReceiptSuccess", ra.get("success"), "pendingReceiptSuccess", rb.get("success"),
                    "settledGold", stock(a).getGold(), "pendingGold", stock(b).getGold(),
                    "settledLoyalty", bean(OfficerRepository.class).findById(oa.getId()).orElseThrow().getLoyalty(),
                    "pendingLoyalty", bean(OfficerRepository.class).findById(ob.getId()).orElseThrow().getLoyalty());
            });
            observe("demobilization_existing_population", () -> {
                Player p = player("r5-demobilization", NOW); p.setCivilianPopulation(1500); building(p, "house", 1);
                bean(ArmyUnitRepository.class).saveAndFlush(new ArmyUnit(null, p.getId(), "infantry", 1));
                var response = bean(ArmyService.class).dismiss(p.getId(), "infantry", 1);
                return Map.of("response", response, "populationBefore", 1500,
                    "populationAfter", bean(PlayerRepository.class).findById(p.getId()).orElseThrow().getCivilianPopulation(),
                    "housingCapacity", bean(TickService.class).popMax(p.getId()));
            });
            observe("building_prestige_cost_snapshot", () -> {
                Player a = player("r5-build-baseline", NOW), b = player("r5-build-research", NOW);
                Map<String, Object> result = new LinkedHashMap<>();
                for (Player p : List.of(a, b)) {
                    building(p, "command", 10); building(p, "house", 5); building(p, "farm", 3);
                    var response = bean(BuildService.class).upgrade(p.getId(), "house", 0);
                    if (!Boolean.TRUE.equals(response.get("success"))) throw new IllegalStateException(response.toString());
                    Construction c = bean(ConstructionRepository.class).findByPlayerIdAndCitySlot(p.getId(), 0).get(0);
                    if (p == b) {
                        TechResearchQueue q = new TechResearchQueue(); q.setPlayerId(p.getId()); q.setTechType("log_build");
                        q.setTargetLevel(1); q.setStartedAt(NOW); q.setFinishesAt(NOW + (c.getFinishAt() - NOW) / 2);
                        q.setDurationSeconds((int) ((q.getFinishesAt() - NOW) / 1000));
                        bean(TechResearchQueueRepository.class).saveAndFlush(q);
                    }
                    at(c.getFinishAt(), () -> bean(BuildService.class).completeUpgrade(p.getId(), c.getFinishAt()));
                    result.put(p == a ? "baseline" : "researchDuringConstruction", Map.of("paidCost", c.getCostSnapshot(),
                        "prestigeAfter", bean(PlayerRepository.class).findById(p.getId()).orElseThrow().getPrestige()));
                }
                return result;
            });
            observe("frozen_city_periodic_inventory_loss", () -> {
                Player p = player("r5-frozen-stock", NOW);
                PlayerCity c = city(p); c.setSurrenderOfferId(999L); c.setSurrenderRecipientId(888L);
                c.setSurrenderFrozenAt(NOW - 60000); c.setSurrenderTransferDeadlineAt(NOW + 3600000);
                bean(PlayerCityRepository.class).saveAndFlush(c);
                long before = ResourceOverflowService.STOCK_LIMIT + 100;
                stock(p).setFood(before); bean(ResourcesRepository.class).saveAndFlush(stock(p));
                var jdbc = bean(org.springframework.jdbc.core.JdbcTemplate.class);
                jdbc.update("DELETE FROM game_schedule WHERE task_key = 'resource-overflow'");
                bean(ResourceOverflowService.class).checkPeriod(100);
                bean(ResourceOverflowService.class).checkPeriod(101);
                return Map.of("cityFrozen", c.isSurrenderFrozen(NOW), "beforeFood", before,
                    "afterFood", stock(p).getFood(), "reports", bean(ScoutReportRepository.class).findByPlayerId(p.getId()).size());
            });
            observe("capped_gold_continuous_balance", () -> Map.of("openingGold", 20000000,
                "taxIncomePerHour", 600, "salaryPerHour", 100,
                "closingGoldAfterHour", TickService.settleBalance(20000000, 600, 100, 1, 20000000)));
            System.out.println("AUDIT_JSON " + com.wargame.util.JsonUtil.toJson(observations));
        } finally { manager.afterTestClass(); app.close(); }
    }
}
