package com.wargame;

import com.wargame.model.entity.Guild;
import com.wargame.model.entity.GuildMember;
import com.wargame.model.entity.Player;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.GuildRepository;
import com.wargame.service.LeaderboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LeaderboardServiceTest extends BaseServiceTest {
    @Autowired private LeaderboardService leaderboards;
    @Autowired private GuildRepository guilds;
    @Autowired private GuildMemberRepository members;

    private Player player(String name, Integer prestige, Integer rank) {
        Player p = createTestPlayer(name, 30);
        p.setPrestige(prestige);
        p.setMilitaryRank(rank);
        return playerRepository.saveAndFlush(p);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entries(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("entries");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mine(Map<String, Object> result) {
        return (Map<String, Object>) result.get("mine");
    }

    private Guild guild(String name, Player leader, Player... others) {
        Guild g = new Guild();
        g.setName(name);
        g.setIcon("g04");
        g.setLeaderPlayerId(leader.getId());
        g.setCreatedAt(1L);
        g = guilds.saveAndFlush(g);
        members.save(new GuildMember(null, g.getId(), leader.getId(), "leader", 1L));
        for (Player p : others) members.save(new GuildMember(null, g.getId(), p.getId(), "member", 2L));
        members.flush();
        return g;
    }

    @Test
    void playerOrderingUsesMetricThenTieBreakersAndOnlyPublicFields() {
        Player first = player("first", 100, 3);
        first.setDisplayName("统帅名");
        playerRepository.saveAndFlush(first);
        Player second = player("second", 100, 3);
        Player highRank = player("highRank", 20, 8);
        Map<String, Object> result = leaderboards.get(second.getId(), "players", "prestige", 1);
        assertEquals(List.of(first.getId(), second.getId(), highRank.getId()), entries(result).stream().map(r -> r.get("id")).toList());
        assertEquals("统帅名", entries(result).get(0).get("name"));
        assertEquals(2L, mine(result).get("rank"));
        assertFalse(entries(result).get(0).containsKey("passwordHash"));
        assertFalse(entries(result).get(0).containsKey("username"));
        assertEquals(highRank.getId(), entries(leaderboards.get(second.getId(), "players", "militaryRank", 1)).get(0).get("id"));
    }

    @Test
    void paginationKeepsGlobalSelfRankAndClampsInvalidPages() {
        Player mine = player("mine", 0, 1);
        for (int i = 0; i < 22; i++) player("ranked" + i, 100 + i, 1);
        Map<String, Object> first = leaderboards.get(mine.getId(), "players", "prestige", -1);
        assertEquals(20, entries(first).size());
        assertEquals(23L, first.get("total"));
        assertEquals(23L, mine(first).get("rank"));
        Map<String, Object> last = leaderboards.get(mine.getId(), "players", "prestige", Integer.MAX_VALUE);
        assertEquals(2, last.get("page"));
        assertEquals(3, entries(last).size());
        assertEquals(21L, entries(last).get(0).get("rank"));
    }

    @Test
    void excludesUnavailableAccountsButIncludesExpiredBansAndNullScores() {
        Player active = player("active", null, null);
        Player uninitialized = player("uninitialized", 900, 9);
        uninitialized.setGameInitialized(false);
        Player deleted = player("deleted", 800, 8);
        deleted.setAccountStatus("DELETED");
        Player pending = player("pending", 700, 7);
        pending.setAccountStatus("PENDING_DELETION");
        Player disabled = player("disabled", 600, 6);
        disabled.setDisabled(1);
        Player banned = player("banned", 500, 5);
        banned.setBanStatus("BANNED");
        banned.setBannedUntil(0L);
        Player temporary = player("temporary", 400, 4);
        temporary.setBanStatus("BANNED");
        temporary.setBannedUntil(System.currentTimeMillis() + 60000);
        Player expired = player("expired", 1, 1);
        expired.setBanStatus("BANNED");
        expired.setBannedUntil(1L);
        playerRepository.saveAllAndFlush(List.of(uninitialized, deleted, pending, disabled, banned, temporary, expired));
        Map<String, Object> result = leaderboards.get(active.getId(), "players", "prestige", 1);
        assertEquals(2L, result.get("total"));
        assertEquals(expired.getId(), entries(result).get(0).get("id"));
        assertEquals(0L, mine(result).get("prestige"));
        assertEquals(1, mine(result).get("militaryRank"));
    }

    @Test
    void guildScoresAggregateEligibleMembersAsLongAndSelfUsesMembership() {
        Player leader = player("leader", Integer.MAX_VALUE, 1);
        Player member = player("member", Integer.MAX_VALUE, 1);
        Player banned = player("banned", 1000, 1);
        banned.setBanStatus("BANNED");
        playerRepository.saveAndFlush(banned);
        Guild high = guild("精锐", leader, member, banned);
        Player other = player("other", 1, 1);
        Guild populous = guild("大团", other, player("member2", 1, 1), player("member3", 1, 1));
        Map<String, Object> result = leaderboards.get(member.getId(), "guilds", "prestige", 1);
        assertEquals(high.getId(), entries(result).get(0).get("id"));
        assertEquals(4294967294L, mine(result).get("prestige"));
        assertEquals(2L, mine(result).get("members"));
        assertEquals("leader", mine(result).get("leaderName"));
        assertEquals(populous.getId(), entries(leaderboards.get(member.getId(), "guilds", "members", 1)).get(0).get("id"));
        assertEquals("精锐", mine(leaderboards.get(member.getId(), "players", "prestige", 1)).get("guildName"));
    }

    @Test
    void emptyBoardsAndUnguildedPlayersHaveNoInventedSelfRank() {
        Map<String, Object> empty = leaderboards.get(999L, "players", "prestige", 1);
        assertEquals(0L, empty.get("total"));
        assertEquals(1, empty.get("totalPages"));
        assertTrue(entries(empty).isEmpty());
        assertNull(empty.get("mine"));
        Player solo = player("solo", 1, 1);
        assertNull(leaderboards.get(solo.getId(), "guilds", "prestige", 1).get("mine"));
    }

    @Test
    void rejectsUnknownTypesAndMetricsBeforeBuildingSql() {
        assertThrows(IllegalArgumentException.class, () -> leaderboards.get(1L, "players; DROP TABLE players", "prestige", 1));
        assertThrows(IllegalArgumentException.class, () -> leaderboards.get(1L, "players", "members", 1));
        assertThrows(IllegalArgumentException.class, () -> leaderboards.get(1L, "guilds", "militaryRank", 1));
    }
}
