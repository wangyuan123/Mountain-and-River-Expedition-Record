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
    void combatAircraftUseAirportWhileScoutKeepsTheFieldFactory() {
        Player player = createTestPlayer("air-production", 30);
        player.setCivilianPopulation(30);
        playerRepository.save(player);
        Long playerId = player.getId();
        giveResources(playerId, 1000, 1000, 1000, 1000, 1000);
        createBuilding(playerId, "factory", 1);

        assertFalse((Boolean) army.recruit(playerId, "fighter", 1).get("success"));
        assertTrue((Boolean) army.recruit(playerId, "scout", 1).get("success"));
        ArmyProductionQueue scout = production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).get(0);
        assertEquals("factory", scout.getBuildingType());

        createBuilding(playerId, "airport", 1);
        assertTrue((Boolean) army.recruit(playerId, "fighter", 1).get("success"));
        ArmyProductionQueue fighter = production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).stream()
                .filter(q -> "fighter".equals(q.getUnitType())).findFirst().orElseThrow();
        assertEquals("airport", fighter.getBuildingType());
    }
}
