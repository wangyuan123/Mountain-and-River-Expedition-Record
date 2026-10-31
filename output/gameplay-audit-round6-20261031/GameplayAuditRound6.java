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

/** Only synthetic cities in a dedicated H2 database; no HTTP server or external messages. */
public class GameplayAuditRound6 {
    @org.springframework.boot.test.context.SpringBootTest(classes = WargameApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:gameplay_audit_round6;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false", "game.clock.enabled=false",
        "game.scheduling.enabled=false", "game.bot.enabled=false", "logging.level.root=ERROR", "spring.main.banner-mode=off"
    })
    @org.springframework.test.context.ActiveProfiles("test")
    static class Harness {
        @org.springframework.boot.test.mock.mockito.MockBean WebSocketPushService pushes;
        @org.springframework.boot.test.mock.mockito.MockBean AccountMailSender mailSender;
    }
    static ConfigurableApplicationContext app;
    static TransactionTemplate tx;
    static final long NOW = 1800000000000L;
    static final Map<String, Object> observations = new LinkedHashMap<>();
    static <T> T bean(Class<T> type) { return app.getBean(type); }
    static Player player(String name) {
        Player p = new Player(); p.setUsername(name); p.setPasswordHash("isolated-synthetic-fixture");
        p.setGameInitialized(true); p.setTax(0); p.setMorale(100); p.setLastTick(NOW);
        p.setCivilianPopulation(0); p.setPrestige(100); p.setCityName(name); p.setCityPosX(10); p.setCityPosY(10);
        p = bean(PlayerRepository.class).saveAndFlush(p);
        city(p, 0); stock(p, 0);
        return p;
    }
    static Resources stock(Player p, int slot) {
        return bean(ResourcesRepository.class).findByPlayerIdAndCitySlot(p.getId(), slot).orElseGet(() -> {
            Resources r = new Resources(); r.setPlayerId(p.getId()); r.setCitySlot(slot);
            r.setGold(100000); r.setFood(10000); r.setSteel(100000); r.setOil(100000); r.setRare(100000);
            return bean(ResourcesRepository.class).saveAndFlush(r);
        });
    }
    static PlayerCity city(Player p, int slot) {
        PlayerCity c = new PlayerCity(); c.setOwnerId(p.getId()); c.setCitySlot(slot); c.setWorldId(1L);
        c.setName(p.getCityName() + "-" + slot); c.setX(10 + slot * 5); c.setY(10); c.setLastTick(NOW); c.setReadyAt(0L);
        return bean(PlayerCityRepository.class).saveAndFlush(c);
    }
    static void frozen(PlayerCity c, long from) {
        c.setSurrenderOfferId(999L); c.setSurrenderRecipientId(888L); c.setSurrenderFrozenAt(from);
        c.setSurrenderTransferDeadlineAt(from + 86400000); bean(PlayerCityRepository.class).saveAndFlush(c);
    }
    static void troops(Player p, int slot) {
        ArmyUnit a = new ArmyUnit(null, p.getId(), "infantry", 100); a.setCitySlot(slot);
        bean(ArmyUnitRepository.class).saveAndFlush(a);
    }
    static void observe(String key, java.util.function.Supplier<Object> work) {
        try { observations.put(key, tx.execute(status -> { status.setRollbackOnly(); return work.get(); })); }
        catch (Throwable error) { observations.put(key + "_check_error", error.toString()); error.printStackTrace(); }
        System.out.println("AUDIT_PROGRESS " + key);
    }
    public static void main(String[] args) throws Exception {
        var manager = new org.springframework.test.context.TestContextManager(Harness.class);
        manager.beforeTestClass(); manager.prepareTestInstance(new Harness());
        app = (ConfigurableApplicationContext) manager.getTestContext().getApplicationContext();
        tx = new TransactionTemplate(bean(PlatformTransactionManager.class));
        try (var clock = GameClock.use(() -> NOW)) {
            observe("frozen_city_global_food_callback", () -> {
                Player p = player("r6-frozen-food");
                PlayerCity c = bean(PlayerCityRepository.class).findByOwnerIdAndCitySlot(p.getId(), 0).orElseThrow();
                c.setGarrisonFoodAt(NOW - 3600000); frozen(c, NOW - 3600000); troops(p, 0);
                long before = stock(p, 0).getFood();
                bean(GarrisonFoodService.class).beforeGlobalChange(p.getId(), NOW);
                return Map.of("frozen", c.isSurrenderFrozen(NOW), "beforeFood", before, "afterFood", stock(p, 0).getFood());
            });
            observe("peaceful_transfer_food_clock", () -> {
                Player from = player("r6-transfer-source"), to = player("r6-transfer-recipient");
                PlayerCity c = city(from, 1); c.setGarrisonFoodAt(NOW - 86400000); frozen(c, NOW - 86400000);
                Resources r = stock(from, 1); troops(from, 1); long before = r.getFood();
                int slot = bean(CityConquestService.class).peacefulTransfer(to.getId(), c, NOW);
                try (var selected = bean(CityScope.class).enter(to.getId(), slot)) {
                    bean(TickService.class).settleEconomyBeforeChange(to.getId());
                }
                return Map.of("beforeFood", before, "afterFood", stock(to, slot).getFood(), "frozenHours", 24,
                    "cityLastTick", c.getLastTick(), "foodLedgerAt", c.getGarrisonFoodAt());
            });
            observe("frozen_wounded_retention", () -> {
                Player p = player("r6-frozen-wounded");
                PlayerCity c = bean(PlayerCityRepository.class).findByOwnerIdAndCitySlot(p.getId(), 0).orElseThrow();
                frozen(c, NOW - 3600000);
                WoundedUnit w = new WoundedUnit(); w.setPlayerId(p.getId()); w.setCitySlot(0); w.setCityId(c.getId());
                w.setCityX(10); w.setCityY(10); w.setCityName(c.getName()); w.setType("infantry"); w.setCount(10);
                w.setCreatedAt(NOW - WoundedService.RETENTION_MS - 1); w.setExpiresAt(NOW - 1); w.setRecoveryPercent(50);
                w = bean(WoundedUnitRepository.class).saveAndFlush(w); Long id = w.getId();
                bean(WoundedService.class).expireWounded(); bean(jakarta.persistence.EntityManager.class).clear();
                return Map.of("frozen", true, "beforeWounded", 10, "retained", bean(WoundedUnitRepository.class).existsById(id));
            });
            observe("vip_healing_quotes", () -> {
                Player p = player("r6-healing-price"); p.setVipLevel(8); bean(PlayerRepository.class).saveAndFlush(p);
                WoundedService service = bean(WoundedService.class);
                return Map.of("unit", "infantry", "count", 220,
                    "discountPercent", Math.round(bean(VipService.class).getWoundedGoldDiscount(p) * 100),
                    "originalGoldPerUnit", service.goldCost("infantry", 1),
                    "goldPerUnit", service.goldCost(p.getId(), "infantry", 1),
                    "goldCost", service.goldCost(p.getId(), "infantry", 220),
                    "diamondCost", service.diamondCost("infantry", 220));
            });
            observe("vip_healing_rounding_quotes", () -> {
                Player p = player("r6-healing-rounding"); p.setVipLevel(5); bean(PlayerRepository.class).saveAndFlush(p);
                WoundedService service = bean(WoundedService.class);
                return Map.of("unit", "infantry", "count", 10,
                    "discountPercent", Math.round(bean(VipService.class).getWoundedGoldDiscount(p) * 100),
                    "originalGoldPerUnit", service.goldCost("infantry", 1),
                    "goldPerUnit", service.goldCost(p.getId(), "infantry", 1),
                    "goldCost", service.goldCost(p.getId(), "infantry", 10),
                    "diamondCost", service.diamondCost("infantry", 10));
            });
            observe("medical_recovery_with_full_skill", () -> {
                Player p = player("r6-medical-recovery");
                Technology tech = new Technology(); tech.setPlayerId(p.getId()); tech.setType("log_medical"); tech.setLevel(10);
                bean(TechnologyRepository.class).saveAndFlush(tech);
                Officer commander = new Officer(); commander.setPlayerId(p.getId());
                commander.setSkills("[{\"id\":\"medic\",\"lv\":5}]");
                return Map.of("medicalLevel", 10, "recoveryPercent", bean(WoundedService.class).recoveryPercent(p.getId(), commander));
            });
            System.out.println("AUDIT_JSON " + JsonUtil.toJson(observations));
        } finally { manager.afterTestClass(); app.close(); }
    }
}
