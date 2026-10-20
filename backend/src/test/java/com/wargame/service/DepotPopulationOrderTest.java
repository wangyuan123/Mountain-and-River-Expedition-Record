package com.wargame.service;

import com.wargame.BaseServiceTest;
import com.wargame.model.entity.Building;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DepotPopulationOrderTest extends BaseServiceTest {
    @Autowired private DepotService depotService;
    @Autowired private CityScope cityScope;
    @Autowired private PlayerItemRepository playerItemRepository;

    @Test
    void emergencyOrderAddsThirtyPercentOfTotalHousingEachUseAndPersists() {
        Player player = createTestPlayer("emergency-population", 30);
        player.setCivilianPopulation(100);
        player.setMorale(35);
        playerRepository.save(player);
        createBuilding(player.getId(), "house", 10);
        createBuilding(player.getId(), "house", 14);
        createBuilding(player.getId(), "farm", 30);
        giveOrders(player, "emergencyPopulationOrder", 2);

        for (int expected : new int[] {8740, 17380}) {
            Map<String, Object> result = depotService.useItem(player.getId(), "emergencyPopulationOrder", null, null);
            assertEquals(true, result.get("success"));
            assertEquals(expected, result.get("civilianPopulation"));
            assertEquals(expected, playerRepository.findById(player.getId()).orElseThrow().getCivilianPopulation());
        }
        assertEquals(0, count(player, "emergencyPopulationOrder"));
    }

    @Test
    void emergencyOrderUsesCapacityAtTimeOfUseAfterHouseUpgrade() {
        Player player = createTestPlayer("emergency-upgrade", 30);
        player.setCivilianPopulation(0);
        playerRepository.save(player);
        Building house = createBuilding(player.getId(), "house", 10);
        giveOrders(player, "emergencyPopulationOrder", 2);
        assertEquals(3600, depotService.useItem(player.getId(), "emergencyPopulationOrder", null, null).get("civilianPopulation"));
        house = buildingRepository.findById(house.getId()).orElseThrow();
        house.setLevel(20);
        buildingRepository.save(house);
        assertEquals(10800, depotService.useItem(player.getId(), "emergencyPopulationOrder", null, null).get("civilianPopulation"));
    }

    @Test
    void bothOrdersFillRemainingRoomAndDoNotConsumeAtOrAboveCapacity() {
        for (String item : new String[] {"populationOrder", "emergencyPopulationOrder"}) {
            Player player = createTestPlayer("cap-" + item, 30);
            player.setCivilianPopulation(11950);
            playerRepository.save(player);
            createBuilding(player.getId(), "house", 10);
            giveOrders(player, item, 2);
            Map<String, Object> result = depotService.useItem(player.getId(), item, null, null);
            assertEquals(true, result.get("success"));
            assertEquals(12000, result.get("civilianPopulation"));
            assertTrue(result.get("message").toString().endsWith("+50"));
            assertEquals(1, count(player, item));
            assertEquals(false, depotService.useItem(player.getId(), item, null, null).get("success"));
            assertEquals(1, count(player, item));
            player = playerRepository.findById(player.getId()).orElseThrow();
            player.setCivilianPopulation(12100);
            playerRepository.save(player);
            assertEquals(false, depotService.useItem(player.getId(), item, null, null).get("success"));
            assertEquals(12100, playerRepository.findById(player.getId()).orElseThrow().getCivilianPopulation());
            assertEquals(1, count(player, item));
        }
    }

    @Test
    void missingItemsAndMissingHousingDoNotChangePopulationOrInventory() {
        Player player = createTestPlayer("emergency-missing", 30);
        player.setCivilianPopulation(50);
        playerRepository.save(player);
        giveOrders(player, "emergencyPopulationOrder", 1);
        assertEquals(false, depotService.useItem(player.getId(), "emergencyPopulationOrder", null, null).get("success"));
        assertEquals(1, count(player, "emergencyPopulationOrder"));
        assertEquals(50, playerRepository.findById(player.getId()).orElseThrow().getCivilianPopulation());
        createBuilding(player.getId(), "house", 10);
        playerItemRepository.tryConsume(player.getId(), "emergencyPopulationOrder", 1, System.currentTimeMillis());
        Map<String, Object> result = depotService.useItem(player.getId(), "emergencyPopulationOrder", null, null);
        assertEquals(false, result.get("success"));
        assertEquals("紧急人口动员令数量不足", result.get("message"));
        assertEquals(50, playerRepository.findById(player.getId()).orElseThrow().getCivilianPopulation());
        assertEquals(0, count(player, "emergencyPopulationOrder"));
    }

    @Test
    void emergencyOrderOnlyUsesSelectedCityHousingAndPopulation() {
        Player player = createTestPlayer("emergency-branch", 30);
        player.setCivilianPopulation(100);
        playerRepository.save(player);
        createBuilding(player.getId(), "house", 20);
        PlayerCity branch = new PlayerCity();
        branch.setWorldId(createTestWorld().getId());
        branch.setOwnerId(player.getId());
        branch.setCitySlot(1);
        branch.setName("紧急动员分城");
        branch.setX(20);
        branch.setY(20);
        branch.setCivilianPopulation(50);
        branch = playerCityRepository.save(branch);
        Building house = new Building();
        house.setPlayerId(player.getId());
        house.setCitySlot(1);
        house.setType("house");
        house.setLevel(10);
        buildingRepository.save(house);
        giveOrders(player, "emergencyPopulationOrder", 1);

        try (CityScope.Scope ignored = cityScope.enter(player.getId(), 1)) {
            Map<String, Object> result = depotService.useItem(player.getId(), "emergencyPopulationOrder", null, null);
            assertEquals(true, result.get("success"));
            assertEquals(3650, result.get("civilianPopulation"));
        }
        assertEquals(3650, playerCityRepository.findById(branch.getId()).orElseThrow().getCivilianPopulation());
        assertEquals(100, playerRepository.findById(player.getId()).orElseThrow().getCivilianPopulation());
        assertEquals(0, count(player, "emergencyPopulationOrder"));
    }

    @Test
    void normalOrderStillAddsFiveHundred() {
        Player player = createTestPlayer("normal-population", 30);
        player.setCivilianPopulation(100);
        playerRepository.save(player);
        createBuilding(player.getId(), "house", 20);
        giveOrders(player, "populationOrder", 1);
        assertEquals(600, depotService.useItem(player.getId(), "populationOrder", null, null).get("civilianPopulation"));
        assertEquals(0, count(player, "populationOrder"));
    }

    private void giveOrders(Player player, String itemId, int count) {
        playerItemRepository.save(new PlayerItem(null, player.getId(), itemId, count, System.currentTimeMillis()));
    }

    private int count(Player player, String itemId) {
        return playerItemRepository.findByPlayerIdAndItemKey(player.getId(), itemId).orElseThrow().getCount();
    }
}
