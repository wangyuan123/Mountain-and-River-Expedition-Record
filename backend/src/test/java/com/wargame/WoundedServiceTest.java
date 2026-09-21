package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.WoundedUnitRepository;
import com.wargame.service.WoundedService;
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
    private Player player;

    @BeforeEach
    void prepare() { player = createTestPlayer("wounded-player", 30); }

    private List<WoundedUnit> batches(Long playerId) {
        return wounded.findByPlayerIdAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(playerId, System.currentTimeMillis());
    }

    private void record(int initial, int alive, long now) {
        service.recordLosses(player.getId(), 10, 10, Map.of("scout", initial), Map.of("scout", alive), null, now);
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
        createMarch(player.getId(), "player", city.getId().toString(), city.getName(), 10, 10, 20, 20,
                Map.of("infantry", 100), "plunder", now - 60000, now - 1, false, false);
        marchService.processMarches(player.getId(), now);
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
