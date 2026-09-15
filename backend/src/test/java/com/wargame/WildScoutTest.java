package com.wargame;

import com.wargame.controller.WildController;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WildScoutTest extends BaseServiceTest {
    private March dispatch(Player player, WildTile tile, boolean legacyEndpoint) {
        if (!legacyEndpoint) return marchService.createDispatch(player.getId(), new DispatchRequest(
                "wild", tile.getId(), "scout", Map.of("scout", 1, "infantry", 10), null, null));
        AuthService auth = mock(AuthService.class);
        GameStateService state = mock(GameStateService.class);
        when(auth.getCurrentPlayer()).thenReturn(player);
        when(state.getGameState(player.getId())).thenReturn(Map.of());
        var response = new WildController(auth, mock(WorldService.class), marchService, state)
                .scout(new GameDtos.WildTileRequest(tile.getId())).getBody();
        assertEquals(true, response.get("success"));
        assertFalse(response.containsKey("garrison"));
        assertFalse(response.containsKey("remaining"));
        return marchRepository.findById((Long) response.get("marchId")).orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scoutingRequiresTravelThenReportsAndReturns(boolean legacyEndpoint) {
        Player player = createTestPlayer();
        Long id = player.getId();
        WildTile tile = createWildTile(createTestWorld().getId(), "oil", 15, 15, 3,
                Map.of("infantry", 10), 5000);
        createBuilding(id, "radar", 1);
        createTechnology(id, "recon_level", 1);
        createArmyUnit(id, "scout", 5);
        createArmyUnit(id, "infantry", 10);
        March march = dispatch(player, tile, legacyEndpoint);
        assertTrue(march.getArriveAt() > march.getStartAt());
        assertEquals(Map.of("scout", 1), JsonUtil.parseIntMap(march.getArmy()));
        assertEquals(4, armyUnitRepository.findByPlayerIdAndType(id, "scout").get(0).getCount());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndType(id, "infantry").get(0).getCount());
        marchService.processMarches(id, march.getArriveAt() - 1);
        assertFalse(tile.getScouted());
        assertFalse(march.getReturning());
        assertTrue(scoutReportRepository.findByPlayerId(id).isEmpty());

        marchService.processMarches(id, march.getArriveAt());
        assertTrue(tile.getScouted());
        assertFalse(tile.getOccupied());
        assertEquals(0, tile.getMined());
        assertTrue(march.getReturning());
        var reports = scoutReportRepository.findByPlayerId(id);
        assertEquals(1, reports.size());
        assertEquals("scout", reports.get(0).getType());
        var data = JsonUtil.parseObjMap(reports.get(0).getData());
        assertEquals(true, data.get("showCityInfo"));
        assertEquals("wild", data.get("targetKind"));
        assertEquals(Map.of("oil", 5000), data.get("resources"));
        assertEquals(4, armyUnitRepository.findByPlayerIdAndType(id, "scout").get(0).getCount());

        marchService.processMarches(id, march.getArriveAt());
        assertTrue(marchRepository.findByPlayerId(id).isEmpty());
        assertEquals(5, armyUnitRepository.findByPlayerIdAndType(id, "scout").get(0).getCount());
        assertEquals(1, scoutReportRepository.findByPlayerId(id).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cannotScoutWithoutRadarOrAircraft(boolean legacyEndpoint) {
        Player player = createTestPlayer();
        WildTile tile = createWildTile(createTestWorld().getId(), "oil", 15, 15, 3, Map.of(), 5000);
        assertEquals("需建造雷达站才能侦察", assertThrows(IllegalArgumentException.class,
                () -> dispatch(player, tile, legacyEndpoint)).getMessage());
        createBuilding(player.getId(), "radar", 1);
        assertThrows(IllegalArgumentException.class, () -> dispatch(player, tile, legacyEndpoint));
        assertFalse(tile.getScouted());
        assertTrue(marchRepository.findByPlayerId(player.getId()).isEmpty());
        assertTrue(scoutReportRepository.findByPlayerId(player.getId()).isEmpty());
    }
}
