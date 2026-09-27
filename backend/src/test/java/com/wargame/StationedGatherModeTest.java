package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.March;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.Resources;
import com.wargame.model.entity.WildTile;
import com.wargame.service.CityScope;
import com.wargame.service.WorldMapService;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StationedGatherModeTest extends BaseServiceTest {
    @Autowired private CityScope cityScope;
    @Autowired private WorldMapService worldMapService;
    @Autowired private EntityManager entityManager;
    @Autowired private ObjectMapper objectMapper;

    private Long playerId;
    private WildTile tile;

    @BeforeEach
    void setUp() {
        playerId = createTestPlayer("stationed-gather", 30).getId();
        tile = createWildTile(createTestWorld().getId(), "grainfield", 15, 15, 1, Map.of(), 1000);
        tile.setOccupied(true);
        tile.setOccupiedBy(playerId);
        wildTileRepository.save(tile);
    }

    @Test
    void automaticGatherTicksWithoutMarchesAndReturnsExactlyOnce() {
        station(0, 10, "[[10,10],[15,10],[15,15]]");
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
        marchService.startWildGather(playerId, tile.getId(), "auto");
        long finishedAt = tile.getGatherEndAt();
        entityManager.flush();
        entityManager.clear();
        tile = wildTileRepository.findById(tile.getId()).orElseThrow();
        assertEquals("auto", tile.getGatherMode());
        assertNotNull(tile.getGarrisonRoutes());
        marchService.processMarches(playerId, finishedAt - 1);
        assertTrue(tile.getGathering());
        marchService.processMarches(playerId, finishedAt);
        March returning = onlyMarch();
        assertTrue(returning.getReturning());
        assertEquals("wild", returning.getTargetKind());
        assertEquals("gather", returning.getAction());
        assertEquals("[[15,15],[15,10],[10,10]]", returning.getRouteData());
        assertEquals(30_000L, returning.getArriveAt() - returning.getStartAt());
        assertEquals(500, tile.getMined());
        assertEquals(1000, getResources(playerId).getFood());
        assertTrue(armyUnitRepository.findByPlayerId(playerId).isEmpty());
        assertEquals("{}", tile.getGarrison());
        assertNull(tile.getGatherHarvested());
        assertNull(tile.getGarrisonRoutes());
        marchService.processMarches(playerId, finishedAt);
        assertEquals(1, marchRepository.findByPlayerId(playerId).size());
        marchService.processMarches(playerId, returning.getArriveAt());
        marchService.processMarches(playerId, returning.getArriveAt() + 1000);
        assertEquals(1500, getResources(playerId).getFood());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0).getCount());
        assertEquals(500, tile.getMined());
        assertTrue(tile.getOccupied());
        assertEquals(playerId, tile.getOccupiedBy());
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
    }

    @Test
    void manualHarvestLeavesCargoAndArmyUntilExplicitReturn() {
        station(0, 10, "[[10,10],[15,10],[15,15]]");
        marchService.startWildGather(playerId, tile.getId(), "manual");
        finishTimeInPast();
        marchService.processMarches(playerId, System.currentTimeMillis());
        assertTrue(tile.getGathering());
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> marchService.recallWild(playerId, tile.getId()));
        assertEquals(false, worldService.abandonWild(playerId, tile.getId()).get("success"));
        Map<String, Object> harvested = marchService.harvestWild(playerId, tile.getId());
        assertEquals(500, harvested.get("harvestAmount"));
        assertEquals(500, marchService.harvestWild(playerId, tile.getId()).get("harvestAmount"));
        assertEquals(500, tile.getMined());
        assertEquals(1000, getResources(playerId).getFood());
        assertEquals(10, JsonUtil.parseIntMap(tile.getGarrison()).get("truck"));
        assertFalse(tile.getGathering());
        assertThrows(IllegalArgumentException.class, () -> marchService.startWildGather(playerId, tile.getId(), "auto"));
        assertEquals(false, worldService.abandonWild(playerId, tile.getId()).get("success"));
        marchService.processMarches(playerId, System.currentTimeMillis() + 600_000L);
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
        marchService.recallWild(playerId, tile.getId());
        assertEquals(1000, getResources(playerId).getFood());
        assertThrows(IllegalArgumentException.class, () -> marchService.recallWild(playerId, tile.getId()));
        March returning = onlyMarch();
        marchService.processMarches(playerId, returning.getArriveAt());
        assertEquals(1500, getResources(playerId).getFood());
        assertTrue(tile.getOccupied());
    }

    @Test
    void partialHarvestIsClampedAndDoesNotResume() {
        station(0, 10, "[[10,10],[15,15]]");
        marchService.startWildGather(playerId, tile.getId(), "manual");
        long now = System.currentTimeMillis();
        tile.setGatherStartAt(now - 50_000L);
        tile.setGatherEndAt(now + 50_000L);
        wildTileRepository.save(tile);
        int amount = (int) marchService.harvestWild(playerId, tile.getId()).get("harvestAmount");
        assertTrue(amount >= 250 && amount <= 260);
        assertEquals(amount, tile.getMined());
        marchService.processMarches(playerId, now + 1_000_000L);
        assertEquals(amount, tile.getGatherHarvested());
        assertEquals(amount, marchService.harvestWild(playerId, tile.getId()).get("harvestAmount"));
        assertEquals(1000, getResources(playerId).getFood());
    }

    @Test
    void futureStartYieldsZeroButStillNeedsExplicitReturn() {
        station(0, 1, "[[10,10],[15,15]]");
        marchService.startWildGather(playerId, tile.getId());
        tile.setGatherStartAt(System.currentTimeMillis() + 60_000L);
        tile.setGatherEndAt(System.currentTimeMillis() + 120_000L);
        assertEquals(0, marchService.harvestWild(playerId, tile.getId()).get("harvestAmount"));
        assertEquals(0, tile.getMined());
        assertEquals(0, tile.getGatherHarvested());
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
    }

    @Test
    void mixedGarrisonsReturnToTheirOwnCitiesWithExactCargoTotal() {
        PlayerCity branch = new PlayerCity();
        branch.setOwnerId(playerId); branch.setCitySlot(1); branch.setWorldId(tile.getWorldId());
        branch.setName("分城"); branch.setX(20); branch.setY(20); branch.setLevel(1);
        playerCityRepository.save(branch);
        Resources resources = new Resources();
        resources.setPlayerId(playerId); resources.setCitySlot(1); resources.setFood(1000);
        resourcesRepository.save(resources);
        station(0, 3, "[[10,10],[15,10],[15,15]]");
        station(1, 7, "[[20,20],[15,20],[15,15]]");
        tile.setTotalRes(499);
        try (var ignored = cityScope.enter(playerId, 1)) {
            marchService.startWildGather(playerId, tile.getId(), "auto");
        }
        long finishedAt = tile.getGatherEndAt();
        marchService.processMarches(playerId, finishedAt);
        assertTrue(tile.getGathering());
        try (var ignored = cityScope.enter(playerId, 1)) { marchService.processMarches(playerId, finishedAt); }
        List<March> returning = marchRepository.findByPlayerId(playerId);
        assertEquals(2, returning.size());
        assertEquals(499, returning.stream().mapToInt(March::getGatherAmount).sum());
        March mainReturn = returning.stream().filter(march -> march.getCitySlot() == 0).findFirst().orElseThrow();
        March branchReturn = returning.stream().filter(march -> march.getCitySlot() == 1).findFirst().orElseThrow();
        assertEquals("[[15,15],[15,20],[20,20]]", branchReturn.getRouteData());
        assertEquals(20, branchReturn.getTargetX());
        marchService.processMarches(playerId, mainReturn.getArriveAt());
        try (var ignored = cityScope.enter(playerId, 1)) { marchService.processMarches(playerId, branchReturn.getArriveAt()); }
        assertEquals(149, resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow().getFood() - 1000);
        assertEquals(350, resourcesRepository.findByPlayerIdAndCitySlot(playerId, 1).orElseThrow().getFood() - 1000);
        assertEquals(3, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "truck").get(0).getCount());
        assertEquals(7, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 1, "truck").get(0).getCount());
        assertTrue(tile.getOccupied());
    }

    @Test
    void legacyGarrisonGetsFallbackRouteAndManualDefault() throws Exception {
        tile.setGarrison(JsonUtil.toJson(Map.of("truck", 2)));
        GameDtos.WildDispatchRequest request = objectMapper.readValue("{\"wildTileId\":" + tile.getId() + "}", GameDtos.WildDispatchRequest.class);
        marchService.startWildGather(playerId, request.wildTileId(), request.gatherMode());
        assertEquals("manual", tile.getGatherMode());
        assertNotNull(tile.getGarrisonRoutes());
        finishTimeInPast();
        marchService.harvestWild(playerId, tile.getId());
        marchService.recallWild(playerId, tile.getId());
        March returning = onlyMarch();
        assertEquals(10, returning.getTargetX());
        assertEquals(10, returning.getTargetY());
        assertTrue(returning.getArriveAt() > returning.getStartAt());
        assertFalse(JsonUtil.parseTree(returning.getRouteData()).isEmpty());
    }

    @Test
    void staleRouteTroopsCannotResurrectLostGarrison() {
        station(0, 10, "[[10,10],[15,15]]");
        tile.setGarrison(JsonUtil.toJson(Map.of("truck", 2)));
        marchService.startWildGather(playerId, tile.getId(), "auto");
        marchService.processMarches(playerId, tile.getGatherEndAt());
        March returning = onlyMarch();
        assertEquals(2, JsonUtil.parseIntMap(returning.getArmy()).get("truck"));
        assertEquals(100, returning.getGatherAmount());
    }

    @Test
    void validatesModeOwnershipAndDisallowsManualCommandsForAuto() {
        station(0, 2, "[[10,10],[15,15]]");
        assertThrows(IllegalArgumentException.class, () -> marchService.startWildGather(playerId, tile.getId(), "bad"));
        Long otherPlayer = createTestPlayer("other-gatherer", 30).getId();
        assertThrows(IllegalArgumentException.class, () -> marchService.startWildGather(otherPlayer, tile.getId(), "manual"));
        marchService.startWildGather(playerId, tile.getId(), "auto");
        assertThrows(IllegalArgumentException.class, () -> marchService.harvestWild(playerId, tile.getId()));
        assertThrows(IllegalArgumentException.class, () -> marchService.recallWild(playerId, tile.getId()));
        Map<String, Object> own = worldMapService.target(playerId, "wild", tile.getId());
        assertEquals("auto", own.get("gatherMode"));
        assertTrue(own.containsKey("gatherHarvested"));
        Map<String, Object> other = worldMapService.target(otherPlayer, "wild", tile.getId());
        assertFalse(other.containsKey("gatherMode"));
        assertFalse(other.containsKey("gatherHarvested"));
    }

    private void station(int slot, int trucks, String route) {
        long now = System.currentTimeMillis();
        March station = createMarch(playerId, "wild", String.valueOf(tile.getId()), "粮田",
                slot == 0 ? 10 : 20, slot == 0 ? 10 : 20, 15, 15, Map.of("truck", trucks), "station",
                now - 60_000L, now - 30_000L, false, false);
        station.setCitySlot(slot);
        station.setRouteMode("land");
        station.setRouteData(route);
        marchRepository.save(station);
        try (var ignored = cityScope.enter(playerId, slot)) { marchService.processMarches(playerId, now); }
    }

    private void finishTimeInPast() {
        tile.setGatherStartAt(System.currentTimeMillis() - 120_000L);
        tile.setGatherEndAt(System.currentTimeMillis() - 60_000L);
        wildTileRepository.save(tile);
    }

    private March onlyMarch() {
        List<March> marches = marchRepository.findByPlayerId(playerId);
        assertEquals(1, marches.size());
        return marches.get(0);
    }
}
