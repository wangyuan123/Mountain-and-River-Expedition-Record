package com.wargame.service;

import com.wargame.model.entity.Player;
import com.wargame.model.entity.WorldMap;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapFavoriteServiceTest {
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final WorldMapRepository worlds = mock(WorldMapRepository.class);
    private final WorldMapService maps = mock(WorldMapService.class);
    private final MapFavoriteService service = new MapFavoriteService(players, worlds, maps, mock(EntityManager.class));
    private Player first;

    @BeforeEach
    void setup() {
        first = new Player(); first.setId(1L);
        Player second = new Player(); second.setId(2L);
        when(players.findById(1L)).thenReturn(Optional.of(first));
        when(players.findById(2L)).thenReturn(Optional.of(second));
        WorldMap world = new WorldMap(); world.setSize(800);
        when(worlds.findFirstByOrderByIdAsc()).thenReturn(Optional.of(world));
    }

    @Test
    void savesFixedCoordinatesOnceWithServerPlayerNameAndIsolatesAccounts() {
        when(maps.target(1L, "player", 9L)).thenReturn(Map.of("x", 65, "y", 147, "name", "远山城", "ownerName", "远山"));
        var request = new MapFavoriteService.SaveRequest(65, 147, "player", 9L);
        assertEquals(List.of(new MapFavoriteService.Favorite(65, 147, "远山")), service.add(1L, request));
        assertEquals(1, service.add(1L, request).size());
        verify(players, times(1)).save(first);
        assertTrue(service.list(2L).isEmpty());
        // 模拟切换城市，坐标快照仍属于账号且不依赖目标存活。
        first.setActiveCityId(22L);
        assertEquals(65, service.list(1L).get(0).x());
        assertTrue(service.remove(1L, 65, 147).isEmpty());
        assertTrue(service.remove(1L, 65, 147).isEmpty());
        verify(maps, times(1)).target(1L, "player", 9L);
    }

    @Test
    void acceptsMapEdgesAndLocalizesWildNamesNewestFirst() {
        service.add(1L, new MapFavoriteService.SaveRequest(0, 799, "site", null));
        when(maps.target(1L, "wild", 3L)).thenReturn(Map.of("x", 799, "y", 0, "name", "oil", "type", "oil", "ownerName", "其他玩家"));
        var result = service.add(1L, new MapFavoriteService.SaveRequest(799, 0, "wild", 3L));
        assertEquals("油田", result.get(0).name());
        assertEquals(new MapFavoriteService.Favorite(0, 799, "坐标标记"), result.get(1));
    }

    @Test
    void rejectsInvalidCoordinatesOrMovedTargetsWithoutChangingFavorites() {
        for (var request : List.of(new MapFavoriteService.SaveRequest(-1, 0, "site", null),
                new MapFavoriteService.SaveRequest(0, 800, "site", null),
                new MapFavoriteService.SaveRequest(null, 0, "site", null),
                new MapFavoriteService.SaveRequest(1, 2, "player", null))) {
            assertThrows(IllegalArgumentException.class, () -> service.add(1L, request));
        }
        when(maps.target(1L, "player", 9L)).thenReturn(Map.of("x", 4, "y", 5, "name", "已迁移"));
        assertThrows(IllegalArgumentException.class, () -> service.add(1L, new MapFavoriteService.SaveRequest(1, 2, "player", 9L)));
        verify(players, never()).save(any());
    }

    @Test
    void limitsUniqueFavoritesButAllowsDuplicateAtLimitAndRemoval() {
        first.setMapFavorites(JsonUtil.toJson(IntStream.range(0, 100).mapToObj(x -> new MapFavoriteService.Favorite(x, 1, "坐标")).toList()));
        assertEquals(100, service.add(1L, new MapFavoriteService.SaveRequest(0, 1, "site", null)).size());
        assertThrows(IllegalArgumentException.class, () -> service.add(1L, new MapFavoriteService.SaveRequest(100, 1, "site", null)));
        assertEquals(99, service.remove(1L, 0, 1).size());
        assertEquals(100, service.add(1L, new MapFavoriteService.SaveRequest(100, 1, "site", null)).size());
    }

    @Test
    void preservesMalformedStoredDataInsteadOfOverwritingIt() {
        first.setMapFavorites("invalid");
        assertThrows(IllegalStateException.class, () -> service.add(1L, new MapFavoriteService.SaveRequest(1, 2, "site", null)));
        assertEquals("invalid", first.getMapFavorites());
        verify(players, never()).save(any());
    }
}
