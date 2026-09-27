package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.ArmyUnit;
import com.wargame.model.entity.March;
import com.wargame.model.entity.Officer;
import com.wargame.model.entity.WildTile;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.CityScope;
import com.wargame.service.WorldViewService;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatherModeTest extends BaseServiceTest {
    @Autowired private WorldViewService worldViewService;
    @Autowired private CityScope cityScope;
    @Autowired private PlayerItemRepository playerItemRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;

    private Long playerId;
    private WildTile tile;

    @BeforeEach
    void setUp() {
        playerId = createTestPlayer("gather-mode-player", 30).getId();
        tile = createWildTile(createTestWorld().getId(), "grainfield", 15, 15, 1, Map.of(), 1000);
        tile.setOccupied(true);
        tile.setOccupiedBy(playerId);
        wildTileRepository.save(tile);
        createArmyUnit(playerId, "truck", 10);
    }

    @Test
    void dispatchPersistsSelectedModeAndAcceptsLegacyRequests() throws Exception {
        DispatchRequest legacy = objectMapper.readValue("{\"targetKind\":\"wild_gather\",\"targetId\":" + tile.getId() +
                ",\"action\":\"gather\",\"army\":{\"truck\":1}}", DispatchRequest.class);
        assertEquals("auto", marchService.createDispatch(playerId, legacy).getGatherMode());
        DispatchRequest selected = objectMapper.readValue("{\"targetKind\":\"wild_gather\",\"targetId\":" + tile.getId() +
                ",\"action\":\"gather\",\"army\":{\"truck\":1},\"gatherMode\":\"manual\"}", DispatchRequest.class);
        Long marchId = marchService.createDispatch(playerId, selected).getId();
        entityManager.flush();
        entityManager.clear();
        March persisted = marchRepository.findById(marchId).orElseThrow();
        assertEquals("manual", persisted.getGatherMode());
        assertFalse(persisted.getGatherStopped());
        assertFalse(persisted.getGathering());
    }

    @Test
    void invalidModesAreRejectedBeforeTroopsAreDeducted() {
        assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(playerId,
                new DispatchRequest("wild_gather", tile.getId(), "gather", Map.of("truck", 1), null, null, "invalid")));
        assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(playerId,
                new DispatchRequest("wild", tile.getId(), "station", Map.of("truck", 1), null, null, "manual")));
        assertEquals(10, armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0).getCount());
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
    }

    @Test
    void automaticGatherReturnsAlongOriginalRouteAndKeepsOwnership() {
        long now = System.currentTimeMillis();
        March march = beginGather("auto", now);
        long completedAt = march.getGatherEndAt();
        marchService.processMarches(playerId, completedAt);
        assertFalse(march.getGathering());
        assertTrue(march.getReturning());
        assertEquals("[[15,15],[15,10],[10,10]]", march.getRouteData());
        assertEquals(30_000L, march.getArriveAt() - march.getStartAt());
        assertEquals(1000, getResources(playerId).getFood());
        marchService.processMarches(playerId, march.getArriveAt());
        assertEquals(1500, getResources(playerId).getFood());
        assertTrue(tile.getOccupied());
        assertEquals(playerId, tile.getOccupiedBy());
    }

    @Test
    void manualGatherWaitsAfterFullLoadAndRequiresTwoCommands() {
        long now = System.currentTimeMillis();
        March march = beginGather("manual", now - 120_000L);
        Officer commander = createOfficer(playerId, "march", 30, 20, 10);
        march.setCommanderId(commander.getId());
        march.setCarryRes(JsonUtil.toJson(Map.of("food", 100)));
        marchRepository.save(march);
        marchService.processMarches(playerId, now + 600_000L);
        assertTrue(march.getGathering());
        assertFalse(march.getReturning());
        assertEquals(1000, getResources(playerId).getFood());
        assertThrows(IllegalArgumentException.class, () -> marchService.cancelMarch(playerId, march.getId()));

        marchService.stopGatherMarch(playerId, march.getId());
        marchService.stopGatherMarch(playerId, march.getId());
        assertFalse(march.getGathering());
        assertTrue(march.getGatherStopped());
        assertFalse(march.getReturning());
        assertEquals(500, march.getGatherAmount());
        marchService.processMarches(playerId, now + 3_600_000L);
        assertTrue(march.getGatherStopped());
        assertFalse(march.getGathering());
        assertFalse(march.getReturning());
        assertEquals("march", commander.getRole());
        assertEquals(1000, getResources(playerId).getFood());
        entityManager.flush();
        entityManager.clear();
        March waiting = marchRepository.findById(march.getId()).orElseThrow();
        assertTrue(waiting.getGatherStopped());
        assertEquals("manual", worldViewService.toMarchMap(waiting, now).get("gatherMode"));
        assertEquals(true, worldViewService.toMarchMap(waiting, now).get("gatherStopped"));

        marchService.cancelMarch(playerId, waiting.getId());
        assertTrue(waiting.getReturning());
        assertEquals("[[15,15],[15,10],[10,10]]", waiting.getRouteData());
        assertEquals(30_000L, waiting.getArriveAt() - waiting.getStartAt());
        assertEquals(10, waiting.getTargetX());
        assertEquals(10, waiting.getTargetY());
        assertThrows(IllegalArgumentException.class, () -> marchService.cancelMarch(playerId, waiting.getId()));
        assertThrows(IllegalArgumentException.class, () -> marchService.stopGatherMarch(playerId, waiting.getId()));
        long returnedAt = waiting.getArriveAt();
        marchService.processMarches(playerId, returnedAt - 1);
        assertEquals(1000, getResources(playerId).getFood());
        marchService.processMarches(playerId, returnedAt);
        marchService.processMarches(playerId, returnedAt + 1);
        assertTrue(marchRepository.findById(waiting.getId()).isEmpty());
        assertEquals(1600, getResources(playerId).getFood());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0).getCount());
        assertEquals("idle", officerRepository.findById(commander.getId()).orElseThrow().getRole());
        WildTile after = wildTileRepository.findById(tile.getId()).orElseThrow();
        assertEquals(500, after.getMined());
        assertTrue(after.getOccupied());
        assertEquals(playerId, after.getOccupiedBy());
    }

    @Test
    void earlyStopKeepsOnlyActualGatheredAmountAndDoesNotGrowWhileWaiting() {
        long now = System.currentTimeMillis();
        March march = beginGather("manual", now - 30_000L);
        long start = march.getGatherStartAt();
        long duration = march.getGatherEndAt() - start;
        assertEquals(50, worldViewService.toMarchMap(march, now).get("progress"));
        long beforeStop = System.currentTimeMillis();
        marchService.stopGatherMarch(playerId, march.getId());
        long afterStop = System.currentTimeMillis();
        int amount = march.getGatherAmount();
        assertTrue(amount >= (int) Math.floor(500D * (beforeStop - start) / duration));
        assertTrue(amount <= (int) Math.floor(500D * (afterStop - start) / duration));
        assertTrue(amount > 0 && amount < 500);
        marchService.processMarches(playerId, now + 3_600_000L);
        marchService.stopGatherMarch(playerId, march.getId());
        assertEquals(amount, march.getGatherAmount());
        marchService.cancelMarch(playerId, march.getId());
        marchService.processMarches(playerId, march.getArriveAt());
        assertEquals(1000 + amount, getResources(playerId).getFood());
        assertEquals(amount, tile.getMined());
    }

    @Test
    void stopRejectsOtherPlayersOtherCitiesAutomaticAndOutboundMarches() {
        long now = System.currentTimeMillis();
        March march = beginGather("manual", now);
        Long otherId = createTestPlayer("other-gather-player", 30).getId();
        assertThrows(IllegalArgumentException.class, () -> marchService.stopGatherMarch(otherId, march.getId()));
        try (var ignored = cityScope.enter(playerId, 1)) {
            assertThrows(IllegalArgumentException.class, () -> marchService.stopGatherMarch(playerId, march.getId()));
            assertThrows(IllegalArgumentException.class, () -> marchService.cancelMarch(playerId, march.getId()));
        }
        march.setGatherMode("auto");
        assertThrows(IllegalArgumentException.class, () -> marchService.stopGatherMarch(playerId, march.getId()));
        march.setGatherMode("manual");
        march.setGathering(false);
        march.setGatherStartAt(null);
        march.setGatherEndAt(0L);
        march.setStartAt(now);
        march.setArriveAt(now + 30_000L);
        assertThrows(IllegalArgumentException.class, () -> marchService.stopGatherMarch(playerId, march.getId()));
        marchService.cancelMarch(playerId, march.getId());
        assertTrue(march.getReturning());
    }

    @Test
    void zeroHarvestGivesNoResourceOrGemReward() {
        March march = beginGather("manual", System.currentTimeMillis());
        march.setGatherStartAt(System.currentTimeMillis() + 60_000L);
        march.setGatherEndAt(march.getGatherStartAt() + 60_000L);
        long itemsBefore = playerItemRepository.count();
        marchService.stopGatherMarch(playerId, march.getId());
        assertEquals(0, march.getGatherAmount());
        marchService.cancelMarch(playerId, march.getId());
        marchService.processMarches(playerId, march.getArriveAt());
        assertEquals(1000, getResources(playerId).getFood());
        assertEquals(itemsBefore, playerItemRepository.count());
        assertTrue(tile.getOccupied());
        assertEquals(playerId, tile.getOccupiedBy());
    }

    private March beginGather(String mode, long gatherStart) {
        March march = createMarch(playerId, "wild_gather", String.valueOf(tile.getId()), "粮田 Lv.1",
                10, 10, 15, 15, Map.of("truck", 10), "gather",
                gatherStart - 60_000L, gatherStart - 30_000L, false, false);
        march.setGatherMode(mode);
        march.setRouteData("[[10,10],[15,10],[15,15]]");
        marchRepository.save(march);
        ArmyUnit trucks = armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0);
        trucks.setCount(0);
        armyUnitRepository.save(trucks);
        marchService.processMarches(playerId, gatherStart);
        assertTrue(march.getGathering());
        assertEquals(gatherStart, march.getGatherStartAt());
        assertEquals(500, march.getGatherAmount());
        return march;
    }
}
