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
        assertFalse(full.stream().filter(t -> nearby.getId().equals(t.get("id"))).findFirst().orElseThrow().containsKey("garrison"));
        var ownedEntry = full.stream().filter(t -> owned.getId().equals(t.get("id"))).findFirst().orElseThrow();
        assertEquals("统帅" + player.getId(), ownedEntry.get("occupiedByName"));
        assertEquals("列兵", ownedEntry.get("occupiedByRankName"));
        assertEquals(player.getPrestige(), ownedEntry.get("occupiedByPrestige"));
        Player visitor = createTestPlayer("map-visitor", 30);
        var visitorTiles = (List<Map<String, Object>>) views.getWorld(visitor.getId(), 150, 150, 3).get("wildTiles");
        var occupiedEntry = visitorTiles.stream().filter(t -> owned.getId().equals(t.get("id"))).findFirst().orElseThrow();
        assertFalse(occupiedEntry.containsKey("garrison"));
        assertEquals("统帅" + player.getId(), occupiedEntry.get("occupiedByName"));
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
    @SuppressWarnings("unchecked")
    void playerCitiesExposeCommanderDisplayNameAndActualMilitaryRank() {
        Player owner = createTestPlayer("city-owner", 30);
        owner.setDisplayName("统帅甲");
        owner.setMilitaryRank(10);
        owner.setPrestige(25000);
        playerRepository.save(owner);
        var city = new com.wargame.model.entity.PlayerCity();
        city.setWorldId(createTestWorld().getId());
        city.setOwnerId(owner.getId());
        city.setName("测试城");
        city.setX(10);
        city.setY(10);
        city.setCitySlot(0);
        playerCityRepository.save(city);
        var entries = (List<Map<String, Object>>) views.getWorld(owner.getId(), 10, 10, 3).get("playerCities");
        assertEquals(1, entries.size());
        assertEquals("统帅甲", entries.get(0).get("commanderName"));
        assertEquals(10, entries.get(0).get("militaryRank"));
        assertEquals("上尉", entries.get(0).get("militaryRankName"));
        assertEquals(25000, entries.get(0).get("prestige"));
        owner.setDisplayName(" ");
        playerRepository.save(owner);
        entries = (List<Map<String, Object>>) views.getWorld(owner.getId(), 10, 10, 3).get("playerCities");
        assertEquals("统帅" + owner.getId(), entries.get(0).get("commanderName"));
        assertEquals(25000, entries.get(0).get("prestige"));
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

    @Test
    void shieldReturnVisibilityIsLimitedToTheDefenderAndEndsAtArrival() {
        Player defender = createTestPlayer("return-defender", 30);
        Player attacker = createTestPlayer("return-attacker", 30);
        Player spectator = createTestPlayer("return-spectator", 30);
        var city = new com.wargame.model.entity.PlayerCity();
        city.setWorldId(createTestWorld().getId());
        city.setOwnerId(defender.getId());
        city.setName("护盾城");
        city.setX(20);
        city.setY(20);
        city = playerCityRepository.save(city);
        long now = System.currentTimeMillis();
        var march = createMarch(attacker.getId(), "player", city.getId().toString(), "敌方主城",
                20, 20, 10, 10, Map.of("infantry", 10), "plunder", now, now + 60_000, true, false);
        assertTrue(views.getReturningEnemyMarches(defender).isEmpty(), "普通撤军不得通过护盾规则泄露路线");
        march.setReturnReason("shield");
        marchRepository.save(march);
        var returns = views.getReturningEnemyMarches(defender);
        assertEquals(1, returns.size(), "守方无需额外升级雷达即可看到护盾拦截后的返程");
        assertEquals(java.util.Set.of("id", "marchId", "targetKind", "fromX", "fromY", "targetX",
                "targetY", "startAt", "arriveAt", "action", "returning"), returns.get(0).keySet());
        assertTrue(views.getReturningEnemyMarches(attacker).isEmpty());
        assertTrue(views.getReturningEnemyMarches(spectator).isEmpty());
        march.setArriveAt(now - 1);
        marchRepository.save(march);
        assertTrue(views.getReturningEnemyMarches(defender).isEmpty(), "归队结算前到达时也应收起返程路线");
    }
}
