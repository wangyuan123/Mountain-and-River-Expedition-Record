package com.wargame;

import com.wargame.controller.WildController;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.March;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.Resources;
import com.wargame.model.entity.WildTile;
import com.wargame.service.AuthService;
import com.wargame.service.CityScope;
import com.wargame.service.GameStateService;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证手动废弃的真实接口在采集、停止待命和召回阶段保护野地、部队与资源。 */
class WildAbandonTest extends BaseServiceTest {
    private static final String GATHER_ERROR = "有采集任务尚未收获，请先收获并下达部队回城命令后再放弃领地";
    private static final String HARVESTED_ERROR = "有部队已收获并留守，请先下达部队回城命令后再放弃领地";
    private static final String OUTBOUND_ERROR = "采集部队尚未召回，请先下达部队回城命令后再放弃领地";

    @Autowired private EntityManager entityManager;
    @Autowired private CityScope cityScope;
    private Long playerId;
    private Long tileId;
    private MockMvc http;
    private GameStateService responseState;

    @BeforeEach
    void setUp() {
        Player player = createTestPlayer("wild-abandon", 30);
        playerId = player.getId();
        WildTile tile = createWildTile(createTestWorld().getId(), "grainfield", 15, 15, 1, Map.of(), 1000);
        tile.setOccupied(true);
        tile.setOccupiedBy(playerId);
        tile.setScouted(true);
        tileId = wildTileRepository.save(tile).getId();
        createArmyUnit(playerId, "truck", 10);

        AuthService auth = mock(AuthService.class);
        when(auth.getCurrentPlayer()).thenReturn(player);
        responseState = mock(GameStateService.class);
        when(responseState.getGameState(anyLong())).thenReturn(Map.of());
        http = MockMvcBuilders.standaloneSetup(new WildController(auth, worldService, marchService, responseState)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"manual"})
    void gatheringAndStoppedMarchMustBeRecalledBeforeAbandoning(String mode) throws Exception {
        March march = dispatchGather(mode);
        marchService.processMarches(playerId, march.getArriveAt());
        assertTrue(march.getGathering());
        assertFalse(Boolean.TRUE.equals(wildTileRepository.findById(tileId).orElseThrow().getGathering()));
        Long marchId = march.getId();
        assertRejectedWithoutChanges(GATHER_ERROR);

        marchService.stopGatherMarch(playerId, marchId);
        March waiting = marchRepository.findById(marchId).orElseThrow();
        assertTrue(waiting.getGatherStopped());
        assertFalse(waiting.getReturning());
        assertRejectedWithoutChanges(HARVESTED_ERROR);

        marchService.cancelMarch(playerId, marchId);
        March returning = marchRepository.findById(marchId).orElseThrow();
        assertTrue(returning.getReturning());
        int cargo = returning.getGatherAmount();
        assertTrue(cargo > 0);
        long food = getResources(playerId).getFood();
        assertAbandoned(tileId);
        assertTrue(marchRepository.existsById(marchId));
        marchService.processMarches(playerId, returning.getArriveAt());
        assertFalse(marchRepository.existsById(marchId));
        assertEquals(10, armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0).getCount());
        assertEquals(food + cargo, getResources(playerId).getFood());
    }

    @ParameterizedTest
    @ValueSource(strings = {"manual"})
    void stationedGatheringAndHarvestedCargoMustBeRecalledBeforeAbandoning(String mode) throws Exception {
        March station = marchService.createDispatch(playerId, new DispatchRequest(
                "wild", tileId, "station", Map.of("truck", 1), null, null));
        marchService.processMarches(playerId, station.getArriveAt());
        marchService.startWildGather(playerId, tileId, mode);
        WildTile gathering = wildTileRepository.findById(tileId).orElseThrow();
        long now = System.currentTimeMillis();
        gathering.setGatherStartAt(now - 60_000L);
        gathering.setGatherEndAt(now + 60_000L);
        wildTileRepository.save(gathering);
        assertRejectedWithoutChanges(GATHER_ERROR);

        marchService.harvestWild(playerId, tileId);
        assertNotNull(wildTileRepository.findById(tileId).orElseThrow().getGatherHarvested());
        assertRejectedWithoutChanges(HARVESTED_ERROR);

        marchService.recallWild(playerId, tileId);
        March returning = marchRepository.findByPlayerId(playerId).get(0);
        int cargo = returning.getGatherAmount();
        assertTrue(cargo > 0);
        long food = getResources(playerId).getFood();
        assertAbandoned(tileId);
        marchService.processMarches(playerId, returning.getArriveAt());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0).getCount());
        assertEquals(food + cargo, getResources(playerId).getFood());
    }

    @Test
    void outboundGatheringTaskAlsoPreventsOrphaningItsArmy() throws Exception {
        dispatchGather("manual");
        assertRejectedWithoutChanges(OUTBOUND_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"truck", "infantry"})
    void abandoningOrdinaryGarrisonReturnsAlongOriginalRouteOnlyOnArrival(String unit) throws Exception {
        if (!"truck".equals(unit)) createArmyUnit(playerId, unit, 10);
        March outbound = marchService.createDispatch(playerId, new DispatchRequest(
                "wild", tileId, "station", Map.of(unit, 2), null, null));
        long duration = outbound.getBaseDurationMs();
        List<Object> reversed = new ArrayList<>();
        JsonUtil.parseTree(outbound.getRouteData()).forEach(reversed::add);
        Collections.reverse(reversed);
        marchService.processMarches(playerId, outbound.getArriveAt());
        assertEquals(8, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, unit).get(0).getCount());

        assertAbandoned(tileId);
        March returning = marchRepository.findByPlayerId(playerId).get(0);
        assertTrue(returning.getReturning());
        assertEquals(15, returning.getFromX());
        assertEquals(15, returning.getFromY());
        assertEquals(10, returning.getTargetX());
        assertEquals(10, returning.getTargetY());
        assertEquals(JsonUtil.toJson(reversed), returning.getRouteData());
        assertEquals(duration, returning.getArriveAt() - returning.getStartAt());
        assertEquals(Map.of(unit, 2), JsonUtil.parseIntMap(returning.getArmy()));
        assertEquals("{}", wildTileRepository.findById(tileId).orElseThrow().getGarrison());
        assertEquals(8, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, unit).get(0).getCount());
        marchService.processMarches(playerId, returning.getArriveAt() - 1);
        assertTrue(marchRepository.existsById(returning.getId()));
        assertEquals(8, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, unit).get(0).getCount());

        marchService.processMarches(playerId, returning.getArriveAt());
        marchService.processMarches(playerId, returning.getArriveAt() + 1000L);
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, unit).get(0).getCount());
        assertEquals(1000, getResources(playerId).getFood());
    }

    @Test
    void abandoningMixedGarrisonReturnsEachTeamToItsOwnCity() throws Exception {
        createBranch();
        March mainOutbound = stationTrucks(0, 2);
        March branchOutbound = stationTrucks(1, 3);
        try (var ignored = cityScope.enter(playerId, 1)) {
            assertAbandoned(tileId);
        }
        List<March> returning = marchRepository.findByPlayerId(playerId);
        assertEquals(2, returning.size());
        for (March march : returning) {
            boolean main = march.getCitySlot() == 0;
            assertTrue(march.getReturning());
            assertEquals(main ? 10 : 20, march.getTargetX());
            assertEquals(main ? 10 : 20, march.getTargetY());
            assertEquals(main ? mainOutbound.getBaseDurationMs() : branchOutbound.getBaseDurationMs(),
                    march.getArriveAt() - march.getStartAt());
            assertEquals(main ? 8 : 7,
                    armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, march.getCitySlot(), "truck").get(0).getCount());
            try (var ignored = cityScope.enter(playerId, march.getCitySlot())) {
                marchService.processMarches(playerId, march.getArriveAt());
            }
        }
        assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "truck").get(0).getCount());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 1, "truck").get(0).getCount());
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
        assertEquals(false, worldService.abandonWild(playerId, tileId).get("success"));
    }

    @Test
    void legacyGarrisonWithoutRoutesReturnsToTerritoryCityEvenWhenAnotherCityIsSelected() throws Exception {
        createBranch();
        WildTile tile = wildTileRepository.findById(tileId).orElseThrow();
        tile.setOccupiedCitySlot(1);
        tile.setGarrison(JsonUtil.toJson(Map.of("truck", 2)));
        wildTileRepository.save(tile);

        assertAbandoned(tileId);
        March returning = marchRepository.findByPlayerId(playerId).get(0);
        assertEquals(1, returning.getCitySlot());
        assertEquals(20, returning.getTargetX());
        assertEquals(20, returning.getTargetY());
        assertTrue(returning.getReturning());
        assertTrue(returning.getArriveAt() > returning.getStartAt());
        assertFalse(JsonUtil.parseTree(returning.getRouteData()).isEmpty());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "truck").get(0).getCount());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 1, "truck").get(0).getCount());
        try (var ignored = cityScope.enter(playerId, 1)) {
            marchService.processMarches(playerId, returning.getArriveAt());
        }
        assertEquals(12, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 1, "truck").get(0).getCount());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "truck").get(0).getCount());
    }

    @Test
    void abandoningLandWithNoLivingGarrisonCreatesNoReturnMarch() throws Exception {
        WildTile tile = wildTileRepository.findById(tileId).orElseThrow();
        tile.setGarrison(JsonUtil.toJson(Map.of("truck", 0)));
        wildTileRepository.save(tile);
        assertAbandoned(tileId);
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "truck").get(0).getCount());
    }

    @Test
    void switchingCitiesCannotBypassGatheringGuard() throws Exception {
        March march = dispatchGather("manual");
        marchService.processMarches(playerId, march.getArriveAt());
        try (var ignored = cityScope.enter(playerId, 1)) {
            assertRejectedWithoutChanges(GATHER_ERROR);
        }
    }

    @Test
    void legacyReturnWithUnsettledResourcesStillPreventsRelease() throws Exception {
        March march = createMarch(playerId, "wild_gather", tileId.toString(), "粮田", 10, 10, 15, 15,
                Map.of("truck", 1), "gather", 1L, Long.MAX_VALUE, true, false);
        march.setGatherResourceSettled(false);
        marchRepository.save(march);
        assertRejectedWithoutChanges("采集资源尚未结算，请稍后再放弃领地");
    }

    @Test
    void zeroHarvestAndMixedTeamsKeepTheCorrectAbandonReason() throws Exception {
        WildTile tile = wildTileRepository.findById(tileId).orElseThrow();
        tile.setGatherHarvested(0);
        wildTileRepository.save(tile);
        assertRejectedWithoutChanges(HARVESTED_ERROR);

        March gathering = dispatchGather("manual");
        marchService.processMarches(playerId, gathering.getArriveAt());
        assertRejectedWithoutChanges(GATHER_ERROR);

        marchService.stopGatherMarch(playerId, gathering.getId());
        assertRejectedWithoutChanges(HARVESTED_ERROR);
    }

    @Test
    void gatheringOnAnotherTileDoesNotPreventAbandoningEmptyLand() throws Exception {
        dispatchGather("manual");
        WildTile other = createWildTile(wildTileRepository.findById(tileId).orElseThrow().getWorldId(),
                "grainfield", 16, 15, 1, Map.of(), 1000);
        other.setOccupied(true);
        other.setOccupiedBy(playerId);
        wildTileRepository.save(other);
        assertAbandoned(other.getId());
        assertTrue(wildTileRepository.findById(tileId).orElseThrow().getOccupied());
        assertEquals(1, marchRepository.findByPlayerId(playerId).size());
    }

    private void createBranch() {
        PlayerCity branch = new PlayerCity();
        branch.setOwnerId(playerId);
        branch.setCitySlot(1);
        branch.setWorldId(wildTileRepository.findById(tileId).orElseThrow().getWorldId());
        branch.setName("分城");
        branch.setX(20);
        branch.setY(20);
        branch.setLevel(1);
        playerCityRepository.save(branch);
        Resources resources = new Resources();
        resources.setPlayerId(playerId);
        resources.setCitySlot(1);
        resources.setFood(1000);
        resources.setOil(1000);
        resourcesRepository.save(resources);
        var army = createArmyUnit(playerId, "truck", 10);
        army.setCitySlot(1);
        armyUnitRepository.save(army);
    }

    private March stationTrucks(int slot, int count) {
        try (var ignored = cityScope.enter(playerId, slot)) {
            March march = marchService.createDispatch(playerId, new DispatchRequest(
                    "wild", tileId, "station", Map.of("truck", count), null, null));
            marchService.processMarches(playerId, march.getArriveAt());
            return march;
        }
    }

    private March dispatchGather(String mode) {
        var commander = createOfficer(playerId, "idle", 30, 20, 10);
        March march = marchService.createDispatch(playerId, new DispatchRequest(
                "wild_gather", tileId, "gather", Map.of("truck", 1), commander.getId(), null, mode));
        // 到达时间置于过去，使停止采集时已有实际资源，验证召回后再废弃也能完整返还。
        long now = System.currentTimeMillis();
        march.setStartAt(now - 60_000L);
        march.setArriveAt(now - 15_000L);
        return marchRepository.save(march);
    }

    private void assertRejectedWithoutChanges(String expectedError) throws Exception {
        String before = snapshot();
        http.perform(post("/api/game/wild/abandon").contentType("application/json")
                        .content(JsonUtil.toJson(Map.of("wildTileId", tileId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(expectedError))
                .andExpect(jsonPath("$.state").doesNotExist());
        assertEquals(before, snapshot(), "拒绝废弃不得修改野地、行军、驻军、军官或库存");
        verify(responseState, never()).getGameState(anyLong());
    }

    private void assertAbandoned(Long id) throws Exception {
        http.perform(post("/api/game/wild/abandon").contentType("application/json")
                        .content(JsonUtil.toJson(Map.of("wildTileId", id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        WildTile tile = wildTileRepository.findById(id).orElseThrow();
        assertFalse(tile.getOccupied());
        assertNull(tile.getOccupiedBy());
    }

    private String snapshot() {
        entityManager.flush();
        entityManager.clear();
        return JsonUtil.toJson(List.of(wildTileRepository.findById(tileId).orElseThrow(),
                marchRepository.findByPlayerId(playerId), armyUnitRepository.findByPlayerId(playerId),
                officerRepository.findByPlayerId(playerId), getResources(playerId)));
    }
}
