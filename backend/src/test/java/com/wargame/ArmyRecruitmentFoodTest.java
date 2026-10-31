package com.wargame;

import com.wargame.model.entity.ArmyProductionQueue;
import com.wargame.model.entity.Building;
import com.wargame.model.entity.Player;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.service.ArmyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class ArmyRecruitmentFoodTest extends BaseServiceTest {
    private AutoCloseable clock;
    @org.junit.jupiter.api.BeforeEach
    void pinOperationTime() {
        long now = System.currentTimeMillis();
        clock = com.wargame.util.GameClock.use(() -> now);
    }
    @org.junit.jupiter.api.AfterEach
    void restoreClock() throws Exception { clock.close(); }

    @Override protected Player createTestPlayer(String name, int tax) {
        Player player = super.createTestPlayer(name, tax);
        player.setLastTick(com.wargame.util.GameClock.now());
        return playerRepository.save(player);
    }

    @Autowired ArmyService army;
    @Autowired ArmyProductionQueueRepository production;

    @Test
    void infantryRequiresAllFourResourcesAndCancellationReturnsPaidCosts() {
        Player player = createTestPlayer();
        player.setCivilianPopulation(10);
        playerRepository.save(player);

        for (String type : new String[] {"factory", "house"}) {
            Building building = new Building();
            building.setPlayerId(player.getId());
            building.setType(type);
            building.setLevel(1);
            buildingRepository.save(building);
        }

        Long playerId = player.getId();
        // 任一征召资源少1都不能创建队列、扣款或扣平民，包括步兵新增的石油与稀矿。
        for (int[] balance : new int[][] {{19, 60, 4, 2}, {20, 59, 4, 2}, {20, 60, 3, 2}, {20, 60, 4, 1}}) {
            giveResources(playerId, balance[0], balance[1], balance[2], balance[3], 500);
            assertFalse((Boolean) army.recruit(playerId, "infantry", 2).get("success"));
            assertEquals(balance[0], getResources(playerId).getFood());
            assertEquals(balance[1], getResources(playerId).getSteel());
            assertEquals(balance[2], getResources(playerId).getOil());
            assertEquals(balance[3], getResources(playerId).getRare());
            assertEquals(10, playerRepository.findById(playerId).orElseThrow().getCivilianPopulation());
            assertTrue(production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).isEmpty());
        }

        giveResources(playerId, 20, 60, 4, 2, 500);
        assertTrue((Boolean) army.recruit(playerId, "infantry", 2).get("success"));
        ArmyProductionQueue queue = production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).get(0);
        assertEquals(20, queue.getCostFood());
        assertEquals(60, queue.getCostSteel());
        assertEquals(4, queue.getCostOil());
        assertEquals(2, queue.getCostRare());
        assertEquals(0, getResources(playerId).getFood());
        assertEquals(0, getResources(playerId).getSteel());
        assertEquals(0, getResources(playerId).getOil());
        assertEquals(0, getResources(playerId).getRare());
        assertEquals(8, playerRepository.findById(playerId).orElseThrow().getCivilianPopulation());

        assertTrue(army.cancelProduction(playerId, queue.getId()));
        assertEquals(20, getResources(playerId).getFood());
        assertEquals(60, getResources(playerId).getSteel());
        assertEquals(4, getResources(playerId).getOil());
        assertEquals(2, getResources(playerId).getRare());
        assertEquals(10, playerRepository.findById(playerId).orElseThrow().getCivilianPopulation());
    }

    @Test
    void allAircraftIncludingScoutRequireAirportBeforeCharging() {
        Player player = createTestPlayer("air-production", 30);
        player.setCivilianPopulation(30);
        playerRepository.save(player);
        Long playerId = player.getId();
        giveResources(playerId, 1000, 1000, 1000, 1000, 1000);
        createBuilding(playerId, "factory", 2);

        for (String type : new String[] {"scout", "fighter", "bomber", "transport"}) {
            var denied = army.recruit(playerId, type, 1);
            assertEquals(false, denied.get("success"));
            assertTrue(denied.get("message").toString().contains("空军基地"));
        }
        assertEquals(1000, getResources(playerId).getFood());
        assertEquals(1000, getResources(playerId).getSteel());
        assertEquals(1000, getResources(playerId).getOil());
        assertEquals(1000, getResources(playerId).getRare());
        assertEquals(30, playerRepository.findById(playerId).orElseThrow().getCivilianPopulation());
        assertTrue(production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).isEmpty());

        createBuilding(playerId, "airport", 2);
        createBuilding(playerId, "apron", 2);
        createTechnology(playerId, "air_engine", 1);
        createTechnology(playerId, "recon_level", 2);
        assertTrue((Boolean) army.recruit(playerId, "scout", 1).get("success"));
        ArmyProductionQueue scout = production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).stream()
                .filter(q -> "scout".equals(q.getUnitType())).findFirst().orElseThrow();
        assertEquals("airport", scout.getBuildingType());
        assertEquals(1, production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).size(), "更高阶空军还需满足各自门槛");
    }

    @Test
    void airportLevelControlsScoutSpeedAndParallelism() {
        Player player = createTestPlayer("scout-capacity", 30);
        player.setCivilianPopulation(30);
        playerRepository.save(player);
        Long playerId = player.getId();
        giveResources(playerId, 10000, 10000, 10000, 10000, 10000);
        createBuilding(playerId, "factory", 2);
        Building airport = createBuilding(playerId, "airport", 2);
        createBuilding(playerId, "apron", 2);
        createTechnology(playerId, "air_engine", 1);
        createTechnology(playerId, "recon_level", 2);
        var slow = army.recruit(playerId, "scout", 1);
        airport.setLevel(5);
        buildingRepository.save(airport);
        var fast = army.recruit(playerId, "scout", 1);
        assertEquals(1, slow.get("parallelQueues"));
        assertEquals(2, fast.get("parallelQueues"));
        assertTrue((int) fast.get("durationSeconds") < (int) slow.get("durationSeconds"));
        ArmyProductionQueue first = production.findById((Long) slow.get("queueId")).orElseThrow();
        ArmyProductionQueue second = production.findById((Long) fast.get("queueId")).orElseThrow();
        assertEquals(1.1, first.getSpeedMultiplier(), 1e-9, "满足解锁门槛的最低空军基地为2级");
        assertEquals(1.25, second.getSpeedMultiplier(), 1e-9);
        assertTrue(second.getStartedAt() < first.getFinishesAt());
    }

    @Test
    void paidFactoryScoutOrdersKeepOriginalLaneAndSchedule() {
        Player player = createTestPlayer("legacy-scout", 30);
        player.setCivilianPopulation(30);
        playerRepository.save(player);
        Long playerId = player.getId();
        giveResources(playerId, 10000, 10000, 10000, 10000, 10000);
        createBuilding(playerId, "factory", 2);
        createBuilding(playerId, "airport", 2);
        createBuilding(playerId, "apron", 2);
        createTechnology(playerId, "air_engine", 1);
        createTechnology(playerId, "recon_level", 2);
        ArmyProductionQueue legacy = new ArmyProductionQueue();
        legacy.setPlayerId(playerId); legacy.setUnitType("scout"); legacy.setBuildingType("factory");
        legacy.setUnitCount(1); legacy.setStartedAt(System.currentTimeMillis());
        legacy.setFinishesAt(legacy.getStartedAt() + 600000); legacy.setDurationSeconds(600);
        legacy.setSpeedMultiplier(1.05); production.save(legacy);
        long finishesAt = legacy.getFinishesAt();
        var scout = army.recruit(playerId, "scout", 1);
        var infantry = army.recruit(playerId, "infantry", 1);
        assertEquals(true, scout.get("success"));
        assertEquals(true, infantry.get("success"));
        assertTrue(production.findById((Long) scout.get("queueId")).orElseThrow().getStartedAt() < finishesAt);
        assertEquals(finishesAt, production.findById((Long) infantry.get("queueId")).orElseThrow().getStartedAt());
        assertEquals(finishesAt, legacy.getFinishesAt());
        assertEquals("factory", legacy.getBuildingType());
    }
}
