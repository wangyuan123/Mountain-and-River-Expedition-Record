package com.wargame;

import com.wargame.model.entity.Player;
import com.wargame.model.entity.Guild;
import com.wargame.model.entity.GuildMember;
import com.wargame.model.entity.GuildRelation;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.WildTile;
import com.wargame.repository.GuildRepository;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.GuildRelationRepository;
import com.wargame.service.WorldMapService;
import com.wargame.service.WorldViewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorldViewServiceTest extends BaseServiceTest {
    @Autowired WorldViewService views;
    @Autowired com.wargame.service.WorldService worldService;
    @Autowired WorldMapService maps;
    @Autowired GuildRepository guilds;
    @Autowired GuildMemberRepository guildMembers;
    @Autowired GuildRelationRepository guildRelations;

    @ParameterizedTest
    @ValueSource(strings = {"neutral", "friendly", "hostile"})
    @SuppressWarnings("unchecked")
    void playerGuildRelationsMatchAcrossListMapNearbyScanAndCoordinateSearch(String relation) {
        Player viewer = createTestPlayer("relation-viewer", 30);
        Player owner = createTestPlayer("relation-owner", 30);
        owner.setCityPosX(11);
        playerRepository.save(owner);
        long worldId = createTestWorld().getId();
        for (Player player : List.of(viewer, owner)) {
            Guild guild = new Guild();
            guild.setName(player.getUsername());
            guild.setLeaderPlayerId(player.getId());
            guild.setCreatedAt(1L);
            guilds.save(guild);
            guildMembers.save(new GuildMember(null, guild.getId(), player.getId(), "leader", 1L));
        }
        if (!"neutral".equals(relation)) {
            long first = guildMembers.findByPlayerId(viewer.getId()).orElseThrow().getGuildId();
            long second = guildMembers.findByPlayerId(owner.getId()).orElseThrow().getGuildId();
            GuildRelation diplomacy = new GuildRelation();
            diplomacy.setGuildLowId(Math.min(first, second));
            diplomacy.setGuildHighId(Math.max(first, second));
            diplomacy.setStatus(relation);
            diplomacy.setUpdatedByPlayerId(viewer.getId());
            diplomacy.setUpdatedAt(1L);
            guildRelations.save(diplomacy);
        }
        for (int slot : List.of(0, 1)) {
            PlayerCity city = new PlayerCity();
            city.setWorldId(worldId);
            city.setOwnerId(owner.getId());
            city.setCitySlot(slot);
            city.setName("relation-city-" + slot);
            city.setX(11 + slot);
            city.setY(10);
            playerCityRepository.save(city);
        }

        var listed = (List<Map<String, Object>>) views.getWorld(viewer.getId(), 10, 10, 3).get("playerCities");
        var nearby = (List<Map<String, Object>>) worldService.getNearbyCities(viewer.getId()).get("playerCities");
        var chunk = (List<Map<String, Object>>) maps.chunk(viewer.getId(), 0, 0).get("targets");
        assertEquals(2, listed.size());
        assertEquals(2, nearby.size());
        for (var city : listed) {
            long id = ((Number) city.get("id")).longValue();
            var scanned = nearby.stream().filter(target -> city.get("id").equals(target.get("id"))).findFirst().orElseThrow();
            var marker = chunk.stream().filter(target -> city.get("id").equals(target.get("id"))).findFirst().orElseThrow();
            var searched = worldService.findAtCoordinate(viewer.getId(), ((Number) city.get("x")).intValue(), 10);
            for (var target : List.of(city, scanned, marker, searched, maps.target(viewer.getId(), "player", id))) {
                assertEquals(relation, target.get("guildRelation"), "主城、分城及所有地图入口应返回相同外交关系");
                assertEquals(false, target.get("guildmate"));
                assertEquals(0L, target.get("warAt"), "军团敌对不能伪造个人宣战倒计时");
                assertEquals(0L, target.get("warEndAt"));
            }
        }
    }

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
        var returns = views.getReturningEnemyMarches(defender);
        assertEquals(1, returns.size(), "守方无需额外升级雷达即可看到撤退返程以实施诱敌");
        assertEquals(java.util.Set.of("id", "marchId", "targetKind", "targetCityId", "countermeasuresUsed",
                "fromName", "targetName", "fromX", "fromY", "targetX", "targetY", "startAt", "arriveAt", "action", "returning"),
                returns.get(0).keySet());
        assertTrue(views.getReturningEnemyMarches(attacker).isEmpty());
        assertTrue(views.getReturningEnemyMarches(spectator).isEmpty());
        march.setArriveAt(now - 1);
        marchRepository.save(march);
        assertTrue(views.getReturningEnemyMarches(defender).isEmpty(), "归队结算前到达时也应收起返程路线");
    }
}
