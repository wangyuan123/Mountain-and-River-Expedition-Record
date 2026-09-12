package com.wargame;

import com.wargame.model.entity.Player;
import com.wargame.model.entity.WildTile;
import com.wargame.service.WorldViewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorldViewServiceTest extends BaseServiceTest {
    @Autowired WorldViewService views;
    @Autowired com.wargame.service.WorldService worldService;

    @Test
    @SuppressWarnings("unchecked")
    void localViewsExcludeDistantTilesButRetainOwnedTilesAndAllowAnExplicitFullMap() {
        Player player = createTestPlayer("map-reader", 30);
        long worldId = createTestWorld().getId();
        WildTile nearby = createWildTile(worldId, "plain", 10, 10, 1, Map.of(), 100);
        WildTile far = createWildTile(worldId, "plain", 100, 100, 1, Map.of(), 100);
        WildTile owned = createWildTile(worldId, "plain", 150, 150, 1, Map.of(), 100);
        owned.setOccupied(true);
        owned.setOccupiedBy(player.getId());
        wildTileRepository.save(owned);

        var local = (List<Map<String, Object>>) views.getWorld(player.getId(), 10, 10, 3).get("wildTiles");
        assertEquals(List.of(nearby.getId(), owned.getId()), local.stream().map(t -> t.get("id")).toList());
        var full = (List<Map<String, Object>>) views.getWorld(player.getId(), 10, 10, 0).get("wildTiles");
        assertEquals(3, full.size());
        var jump = (List<Map<String, Object>>) views.getWorld(player.getId(), 100, 100, 3).get("wildTiles");
        assertTrue(jump.stream().anyMatch(t -> far.getId().equals(t.get("id"))));
        assertEquals(far.getId(), worldService.findAtCoordinate(player.getId(), 100, 100).get("id"));
        var scanned = (List<Map<String, Object>>) worldService.getNearbyCities(player.getId()).get("wildTiles");
        assertTrue(scanned.stream().anyMatch(t -> nearby.getId().equals(t.get("id"))));
        assertFalse(scanned.stream().anyMatch(t -> far.getId().equals(t.get("id"))));
        assertThrows(IllegalArgumentException.class, () -> views.getWorld(player.getId(), -1, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> views.getWorld(player.getId(), 0, 0, 201));
    }

    @Test
    void duePlayerQuerySkipsFreshPlayersAndUsesKeysetPagination() {
        Player due = createTestPlayer("due-player", 30);
        due.setLastTick(100L);
        playerRepository.save(due);
        Player fresh = createTestPlayer("fresh-player", 30);
        fresh.setLastTick(1000L);
        playerRepository.save(fresh);
        var ids = playerRepository.findDuePlayerIds(0, 500, PageRequest.of(0, 1));
        assertEquals(List.of(due.getId()), ids);
        assertTrue(playerRepository.findDuePlayerIds(due.getId(), 500, PageRequest.of(0, 1)).isEmpty());
    }
}
