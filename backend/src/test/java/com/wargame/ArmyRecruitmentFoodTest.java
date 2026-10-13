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
    @Autowired ArmyService army;
    @Autowired ArmyProductionQueueRepository production;

    @Test
    void recruitingRequiresFoodAndCancellationReturnsIt() {
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
        giveResources(playerId, 19, 1000, 1000, 500, 500);
        assertFalse((Boolean) army.recruit(playerId, "infantry", 2).get("success"));
        assertEquals(19, getResources(playerId).getFood());
        assertTrue(production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).isEmpty());

        giveResources(playerId, 20, 1000, 1000, 500, 500);
        assertTrue((Boolean) army.recruit(playerId, "infantry", 2).get("success"));
        ArmyProductionQueue queue = production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).get(0);
        assertEquals(20, queue.getCostFood());
        assertEquals(0, getResources(playerId).getFood());
        assertEquals(8, playerRepository.findById(playerId).orElseThrow().getCivilianPopulation());

        assertTrue(army.cancelProduction(playerId, queue.getId()));
        assertEquals(20, getResources(playerId).getFood());
        assertEquals(10, playerRepository.findById(playerId).orElseThrow().getCivilianPopulation());
    }

    @Test
    void allAircraftIncludingScoutRequireAirportBeforeCharging() {
        Player player = createTestPlayer("air-production", 30);
        player.setCivilianPopulation(30);
        playerRepository.save(player);
        Long playerId = player.getId();
        giveResources(playerId, 1000, 1000, 1000, 1000, 1000);
        createBuilding(playerId, "factory", 1);

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

        createBuilding(playerId, "airport", 1);
        assertTrue((Boolean) army.recruit(playerId, "scout", 1).get("success"));
        assertTrue((Boolean) army.recruit(playerId, "fighter", 1).get("success"));
        ArmyProductionQueue scout = production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).stream()
                .filter(q -> "scout".equals(q.getUnitType())).findFirst().orElseThrow();
        assertEquals("airport", scout.getBuildingType());
        ArmyProductionQueue fighter = production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).stream()
                .filter(q -> "fighter".equals(q.getUnitType())).findFirst().orElseThrow();
        assertEquals("airport", fighter.getBuildingType());
        assertEquals(scout.getFinishesAt(), fighter.getStartedAt(), "侦察机与战斗机共享空军基地通道");
    }

    @Test
    void airportLevelControlsScoutSpeedAndParallelism() {
        Player player = createTestPlayer("scout-capacity", 30);
        player.setCivilianPopulation(30);
        playerRepository.save(player);
        Long playerId = player.getId();
        giveResources(playerId, 10000, 10000, 10000, 10000, 10000);
        createBuilding(playerId, "factory", 10);
        Building airport = createBuilding(playerId, "airport", 1);
        var slow = army.recruit(playerId, "scout", 1);
        airport.setLevel(5);
        buildingRepository.save(airport);
        var fast = army.recruit(playerId, "scout", 1);
        assertEquals(1, slow.get("parallelQueues"));
        assertEquals(2, fast.get("parallelQueues"));
        assertTrue((int) fast.get("durationSeconds") < (int) slow.get("durationSeconds"));
        ArmyProductionQueue first = production.findById((Long) slow.get("queueId")).orElseThrow();
        ArmyProductionQueue second = production.findById((Long) fast.get("queueId")).orElseThrow();
        assertEquals(1.05, first.getSpeedMultiplier(), 1e-9);
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
        createBuilding(playerId, "factory", 1);
        createBuilding(playerId, "airport", 1);
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
