package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.WoundedUnitRepository;
import com.wargame.service.WoundedService;
import com.wargame.service.CityScope;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WoundedServiceTest extends BaseServiceTest {
    @Autowired WoundedService service;
    @Autowired WoundedUnitRepository wounded;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CityScope scope;
    private Player player;

    @BeforeEach
    void prepare() { player = createTestPlayer("wounded-player", 30); }

    private List<WoundedUnit> batches(Long playerId) {
        return wounded.findByPlayerIdAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(playerId, System.currentTimeMillis());
    }

    private void record(int initial, int alive, long now) {
        service.recordLosses(player.getId(), 10, 10, Map.of("scout", initial), Map.of("scout", alive), null, now);
    }

    /** 玩家城战斗必须处于战争期，且逐回合推进完毕后才生成最终战报和伤兵。 */
    private void finishCityBattle(PlayerCity city, Map<String, Integer> army, long now) {
        player.setWarAgainstId(city.getOwnerId());
        player.setWarAt(now - 60000);
        player.setWarEndAt(now + 3600000);
        playerRepository.save(player);
        March march = createMarch(player.getId(), "player", city.getId().toString(), city.getName(),
                10, 10, city.getX(), city.getY(), army, "plunder", now - 60000, now - 1, false, false);
        marchService.processMarches(player.getId(), now);
        assertNotNull(march.getBattleId(), "玩家城抵达后应建立战术会话");
        for (int i = 0; i < 40 && march.getBattleId() != null; i++) {
            marchService.executeTacticalRound(player.getId(), march.getId(), null);
        }
        assertNull(march.getBattleId(), "战斗须完成结算后再检查兵力");
    }

    private PlayerCity createTestCity(String name, Long owner, int x, int y) {
        PlayerCity city = new PlayerCity();
        city.setWorldId(createTestWorld().getId());
        city.setOwnerId(owner);
        city.setName(name);
        city.setX(x);
        city.setY(y);
        city.setLevel(1);
        city.setForts("{}");
        city.setResources("{}");
        return playerCityRepository.save(city);
    }

    @Test
    void noBaseRecoveryAndMedicalPlusParticipatingMedicAreCappedAndRoundedDown() {
        record(100, 0, System.currentTimeMillis());
        assertTrue(batches(player.getId()).isEmpty());
        createTechnology(player.getId(), "log_medical", 10);
        Officer medic = createOfficer(player.getId(), "idle", 30, 30, 30);
        medic.setSkills("[{\"id\":\"medic\",\"lv\":5}]");
        assertEquals(50, service.recoveryPercent(player.getId(), null), "闲置军官不参与计算");
        assertEquals(65, service.recoveryPercent(player.getId(), medic));
        medic.setSkills("[{\"id\":\"medic\",\"lv\":999}]");
        assertEquals(65, service.recoveryPercent(player.getId(), medic));
        var result = service.recordLosses(player.getId(), 10, 10,
                Map.of("scout", 101, "infantry", 3, "wall", 100), Map.of("scout", 1), medic, System.currentTimeMillis());
        assertEquals(Map.of("scout", 65, "infantry", 1), result);
        assertEquals(2, batches(player.getId()).size());
    }

    @Test
    void citiesAndBattleBatchesRetainSeparateDeadlinesAndRates() {
        Technology tech = createTechnology(player.getId(), "log_medical", 1);
        long firstAt = System.currentTimeMillis() - 3600000;
        record(100, 0, firstAt);
        tech.setLevel(10);
        technologyRepository.save(tech);
        record(100, 0, firstAt + 1000);
        service.recordLosses(player.getId(), 20, 20, Map.of("scout", 100), Map.of(), null, firstAt + 2000);
        var records = batches(player.getId());
        assertEquals(3, records.size());
        assertEquals(5, records.get(0).getCount());
        assertEquals(50, records.get(1).getCount());
        assertEquals(firstAt + WoundedService.RETENTION_MS, records.get(0).getExpiresAt());
        assertEquals(firstAt + 1000 + WoundedService.RETENTION_MS, records.get(1).getExpiresAt());
        assertEquals(20, records.get(2).getCityX());
        service.heal(player.getId(), records.get(0).getId(), 2, "gold");
        assertEquals(3, records.get(0).getCount());
        assertEquals(50, records.get(2).getCount());
        assertEquals(firstAt + WoundedService.RETENTION_MS, records.get(0).getExpiresAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gold", "diamond"})
    void bothCurrenciesRestoreSameTroopsImmediatelyAndCannotHealTwice(String currency) {
        createTechnology(player.getId(), "log_medical", 10);
        record(100, 0, System.currentTimeMillis());
        var batch = batches(player.getId()).get(0);
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setGold("gold".equals(currency) ? 10000 : 0);
        res.setDiamond(100);
        resourcesRepository.save(res);
        long expected = "gold".equals(currency) ? service.goldCost("scout", 50) : service.diamondCost("scout", 50);
        var result = service.heal(player.getId(), batch.getId(), 50, currency);
        assertEquals(expected, result.get("cost"));
        assertEquals(50, armyUnitRepository.findByPlayerIdAndType(player.getId(), "scout").get(0).getCount());
        assertTrue(batches(player.getId()).isEmpty());
        assertEquals("gold".equals(currency) ? 10000 - expected : 0, (long) res.getGold());
        assertEquals("diamond".equals(currency) ? 100 - expected : 100, (long) res.getDiamond());
        assertThrows(IllegalArgumentException.class, () -> service.heal(player.getId(), batch.getId(), 50, currency));
    }

    @Test
    void diamondsRoundUpPerThousandGoldForQuotesAndActualTreatment() {
        // 步兵四项资源合计43，黄金治疗单价向上取整为5，钻石边界随之变为每200人一颗。
        assertEquals(5, service.goldCost("infantry", 1));
        assertEquals(1, service.diamondCost("infantry", 1));
        assertEquals(1, service.diamondCost("infantry", 199));
        assertEquals(1, service.diamondCost("infantry", 200));
        assertEquals(2, service.diamondCost("infantry", 201));
        assertEquals(1552, service.diamondCost("destroyer", 20410));
        createTechnology(player.getId(), "log_medical", 10);
        service.recordLosses(player.getId(), 10, 10, Map.of("infantry", 402), Map.of(), null, System.currentTimeMillis());
        var batch = batches(player.getId()).get(0);
        Resources balance = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        balance.setGold(0);
        balance.setDiamond(1);
        resourcesRepository.save(balance);
        var quote = (List<?>) service.getCamp(player.getId()).get("batches");
        assertEquals(2L, ((Map<?, ?>) quote.get(0)).get("diamondCost"));
        assertThrows(IllegalArgumentException.class, () -> service.heal(player.getId(), batch.getId(), 201, "diamond"));
        assertEquals(1, balance.getDiamond());
        assertEquals(201, batch.getCount());
        var result = service.heal(player.getId(), batch.getId(), 200, "diamond");
        assertEquals(1L, result.get("cost"));
        assertEquals(0, balance.getDiamond());
        assertEquals(0, balance.getGold());
        assertEquals(1, batch.getCount());
        assertEquals(200, armyUnitRepository.findByPlayerIdAndType(player.getId(), "infantry").get(0).getCount());
    }

    @Test
    void rejectsInsufficientFundsOtherPlayersExpiredBatchesAndInvalidQuantities() {
        createTechnology(player.getId(), "log_medical", 10);
        record(100, 0, System.currentTimeMillis());
        var batch = batches(player.getId()).get(0);
        Long other = createTestPlayer("wounded-other", 30).getId();
        assertThrows(IllegalArgumentException.class, () -> service.heal(other, batch.getId(), 1, "gold"));
        assertThrows(IllegalArgumentException.class, () -> service.heal(player.getId(), batch.getId(), 0, "gold"));
        assertThrows(IllegalArgumentException.class, () -> service.heal(player.getId(), batch.getId(), 51, "gold"));
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setGold(100);
        resourcesRepository.save(res);
        assertThrows(IllegalArgumentException.class, () -> service.heal(player.getId(), batch.getId(), 50, "gold"));
        assertEquals(50, batch.getCount());
        assertEquals(100, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getGold());
        assertTrue(armyUnitRepository.findByPlayerId(player.getId()).isEmpty());
        batch.setExpiresAt(System.currentTimeMillis() - 1);
        wounded.save(batch);
        assertThrows(IllegalArgumentException.class, () -> service.heal(player.getId(), batch.getId(), 1, "gold"));
        assertEquals(0L, service.getCamp(player.getId()).get("total"));
        service.expireWounded();
        assertFalse(wounded.existsById(batch.getId()));
    }

    @Test
    void scoutCombatRecordsBothSidesExactlyOnceUsingActualLosses() {
        Player defender = createTestPlayer("scout-defender", 30);
        PlayerCity city = createTestCity("被侦查城", defender.getId(), 20, 20);
        createArmyUnit(defender.getId(), "scout", 100);
        createTechnology(defender.getId(), "log_medical", 10);
        createTechnology(player.getId(), "log_medical", 10);
        long now = System.currentTimeMillis();
        createMarch(player.getId(), "player", city.getId().toString(), city.getName(), 10, 10, 20, 20,
                Map.of("scout", 100), "scout", now - 60000, now - 1, false, false);
        marchService.processMarches(player.getId(), now);
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(player.getId()).get(0).getData());
        int myLoss = ((Number) report.get("myLost")).intValue();
        int enemyLoss = ((Number) report.get("enemyLost")).intValue();
        assertEquals(myLoss / 2, batches(player.getId()).stream().mapToInt(WoundedUnit::getCount).sum());
        assertEquals(enemyLoss / 2, batches(defender.getId()).stream().mapToInt(WoundedUnit::getCount).sum());
        assertEquals(100 - enemyLoss, armyUnitRepository.findByPlayerIdAndType(defender.getId(), "scout").get(0).getCount());
        int before = wounded.findAll().size();
        marchService.processMarches(player.getId(), now);
        assertEquals(before, wounded.findAll().size());
        assertTrue(batches(player.getId()).stream().allMatch(w -> w.getCityX() == 10));
        assertTrue(batches(defender.getId()).stream().allMatch(w -> w.getCityX() == 20));
    }

    @Test
    void cityCombatRecordsBothSidesFromReportLossesIncludingDefeat() {
        Player defender = createTestPlayer("battle-defender", 30);
        PlayerCity city = createTestCity("战斗城", defender.getId(), 20, 20);
        createArmyUnit(defender.getId(), "infantry", 1000);
        createTechnology(defender.getId(), "log_medical", 10);
        createTechnology(player.getId(), "log_medical", 10);
        long now = System.currentTimeMillis();
        finishCityBattle(city, Map.of("infantry", 100), now);
        Map<String, Object> report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(player.getId()).get(0).getData());
        @SuppressWarnings("unchecked") var alive = (Map<String, Number>) report.get("survivorAttacker");
        assertEquals((100 - alive.getOrDefault("infantry", 0).intValue()) / 2,
                batches(player.getId()).stream().mapToInt(WoundedUnit::getCount).sum());
        @SuppressWarnings("unchecked") var defended = (Map<String, Number>) report.get("survivorDefender");
        assertEquals((1000 - defended.getOrDefault("infantry", 0).intValue()) / 2,
                batches(defender.getId()).stream().mapToInt(WoundedUnit::getCount).sum());
    }

    @Test
    void wildDefeatCreatesWoundedAndExcludesUnsentTroops() {
        createTechnology(player.getId(), "log_medical", 10);
        createArmyUnit(player.getId(), "infantry", 777);
        var tile = createWildTile(createTestWorld().getId(), "forest", 20, 20, 1, Map.of("infantry", 1000), 0);
        long now = System.currentTimeMillis();
        createMarch(player.getId(), "wild", tile.getId().toString(), "森林", 10, 10, 20, 20,
                Map.of("infantry", 100), "conquer", now - 60000, now - 1, false, false);
        marchService.processMarches(player.getId(), now);
        assertFalse(batches(player.getId()).isEmpty());
        assertTrue(batches(player.getId()).stream().mapToInt(WoundedUnit::getCount).sum() <= 50);
        assertEquals(777, armyUnitRepository.findByPlayerIdAndType(player.getId(), "infantry").get(0).getCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gold", "diamond"})
    void annihilatedDefendersStayOutOfHomeArmyUntilPaidTreatment(String currency) {
        Player defender = createTestPlayer("annihilated-defender", 30);
        PlayerCity city = createTestCity("全灭守城", defender.getId(), 20, 20);
        city.setCitySlot(0);
        playerCityRepository.save(city);
        createArmyUnit(defender.getId(), "infantry", 10);
        createArmyUnit(defender.getId(), "truck", 4);
        createTechnology(defender.getId(), "log_medical", 10);
        long now = System.currentTimeMillis();
        finishCityBattle(city, Map.of("infantry", 10000), now);

        Map<String, Object> report = JsonUtil.parseObjMap(
                scoutReportRepository.findByPlayerId(defender.getId()).get(0).getData());
        assertTrue(JsonUtil.parseIntMap(JsonUtil.toJson(report.get("survivorDefender"))).isEmpty());
        assertEquals(7, batches(defender.getId()).stream().mapToInt(WoundedUnit::getCount).sum());
        Map<String, Object> before = gameStateService.getGameState(defender.getId());
        assertEquals(0, JsonUtil.parseIntMap(JsonUtil.toJson(before.get("army"))).values()
                .stream().mapToInt(Integer::intValue).sum());
        assertEquals(7L, before.get("woundedCount"));

        Resources balance = resourcesRepository.findByPlayerId(defender.getId()).orElseThrow();
        balance.setGold(10000);
        balance.setDiamond(100);
        resourcesRepository.save(balance);
        WoundedUnit infantry = batches(defender.getId()).stream()
                .filter(batch -> "infantry".equals(batch.getType())).findFirst().orElseThrow();
        service.heal(defender.getId(), infantry.getId(), 2, currency);
        Map<String, Object> after = gameStateService.getGameState(defender.getId());
        Map<String, Integer> army = JsonUtil.parseIntMap(JsonUtil.toJson(after.get("army")));
        assertEquals(2, army.get("infantry"));
        assertEquals(0, army.getOrDefault("truck", 0));
        assertEquals(5L, after.get("woundedCount"));
        assertEquals("gold".equals(currency) ? 10000 - service.goldCost("infantry", 2) : 10000, balance.getGold().longValue());
        assertEquals("diamond".equals(currency) ? 100 - service.diamondCost("infantry", 2) : 100, balance.getDiamond().longValue());
    }

    @Test
    void annihilatedSortieDoesNotClearUnsentReservesOrCountWoundedAsArmy() {
        Player defender = createTestPlayer("reserve-defender", 30);
        defender.setSortieArmy("{\"infantry\":10}");
        playerRepository.save(defender);
        PlayerCity city = createTestCity("预备兵守城", defender.getId(), 20, 20);
        city.setCitySlot(0);
        playerCityRepository.save(city);
        createArmyUnit(defender.getId(), "infantry", 17);
        createTechnology(defender.getId(), "log_medical", 10);
        long now = System.currentTimeMillis();
        finishCityBattle(city, Map.of("infantry", 10000), now);

        Map<String, Object> report = JsonUtil.parseObjMap(
                scoutReportRepository.findByPlayerId(defender.getId()).get(0).getData());
        assertEquals(Map.of("infantry", 10), JsonUtil.parseIntMap(JsonUtil.toJson(report.get("initialDefender"))));
        assertTrue(JsonUtil.parseIntMap(JsonUtil.toJson(report.get("survivorDefender"))).isEmpty());
        Map<String, Object> state = gameStateService.getGameState(defender.getId());
        assertEquals(Map.of("infantry", 7), JsonUtil.parseIntMap(JsonUtil.toJson(state.get("army"))));
        assertEquals(5L, state.get("woundedCount"));
    }

    @Test
    void homeWoundedCountMatchesCurrentCityCampAndDoesNotIncludeOtherCities() {
        createTestWorld();
        createTechnology(player.getId(), "log_medical", 10);
        record(20, 0, System.currentTimeMillis());
        WoundedUnit branch = new WoundedUnit();
        branch.setPlayerId(player.getId());
        branch.setCitySlot(1);
        branch.setCityX(20);
        branch.setCityY(20);
        branch.setCityName("分城");
        branch.setType("scout");
        branch.setCount(50);
        branch.setCreatedAt(System.currentTimeMillis());
        branch.setExpiresAt(System.currentTimeMillis() + WoundedService.RETENTION_MS);
        branch.setRecoveryPercent(50);
        wounded.save(branch);
        assertEquals(10L, service.getCamp(player.getId()).get("total"));
        assertEquals(10L, gameStateService.getGameState(player.getId()).get("woundedCount"));
        assertTrue(JsonUtil.parseIntMap(JsonUtil.toJson(gameStateService.getGameState(player.getId()).get("army"))).isEmpty());
    }

    @Test
    void outboundWoundedKeepMarchCityWhenCoordinatesDifferAndAnotherCityIsActive() {
        PlayerCity main = createTestCity("主城", player.getId(), 10, 10);
        main.setCitySlot(0);
        playerCityRepository.save(main);
        PlayerCity branch = createTestCity("分城", player.getId(), 20, 20);
        branch.setCitySlot(1);
        playerCityRepository.save(branch);
        player.setActiveCityId(main.getId());
        playerRepository.save(player);
        createTechnology(player.getId(), "log_medical", 10);
        March outbound = new March();
        outbound.setPlayerId(player.getId());
        outbound.setCitySlot(1);
        outbound.setFromX(90);
        outbound.setFromY(90);
        service.recordOutboundLosses(player.getId(), outbound, Map.of("scout", 20), Map.of(), null, System.currentTimeMillis());
        WoundedUnit batch = batches(player.getId()).get(0);
        assertEquals(1, batch.getCitySlot());
        assertEquals(branch.getId(), batch.getCityId());
        assertEquals(20, batch.getCityX());
        assertEquals(20, batch.getCityY());
        assertEquals(0, scope.slot(player.getId()), "记录伤兵后须恢复调用方的城市上下文");
        assertEquals(0L, service.getCamp(player.getId()).get("total"));
        try (var ignored = scope.enter(branch)) {
            assertEquals(10L, service.getCamp(player.getId()).get("total"));
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDoubleClickOnlyHealsAndChargesOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long id = tx.execute(status -> {
            createTechnology(player.getId(), "log_medical", 10);
            record(20, 0, System.currentTimeMillis());
            return batches(player.getId()).get(0).getId();
        });
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        Callable<Boolean> heal = () -> {
            ready.countDown();
            go.await();
            try { service.heal(player.getId(), id, 10, "gold"); return true; }
            catch (IllegalArgumentException e) { return false; }
        };
        try {
            var a = pool.submit(heal);
            var b = pool.submit(heal);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            int successes = (a.get(15, TimeUnit.SECONDS) ? 1 : 0) + (b.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(10, armyUnitRepository.findByPlayerIdAndType(player.getId(), "scout").get(0).getCount());
            assertEquals(500 - service.goldCost("scout", 10), resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getGold().longValue());
        } finally {
            pool.shutdownNow();
            tx.executeWithoutResult(status -> {
                wounded.deleteByPlayerId(player.getId()); armyUnitRepository.deleteByPlayerId(player.getId());
                technologyRepository.deleteByPlayerId(player.getId()); resourcesRepository.deleteByPlayerId(player.getId());
                playerRepository.deleteById(player.getId());
            });
        }
    }
}
