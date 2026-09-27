package com.wargame.service;

import com.wargame.model.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class AcademyRefreshPolicyTest {
    @Test
    void midnightResetsOnlyDailyCountAndPreservesAnUnfinishedCooldown() {
        long beforeMidnight = Instant.parse("2026-09-26T15:59:59Z").toEpochMilli();
        Player player = new Player();
        player.setAcademyRefreshDay(LocalDate.of(2026, 9, 26));
        player.setAcademyRefreshDailyCount(100);
        player.setAcademyRefreshRoundCount(30);
        player.setAcademyRefreshAt(beforeMidnight + 3600_000L);
        var before = AcademyRefreshPolicy.snapshot(player, beforeMidnight);
        assertEquals(100, before.dailyCount());
        assertEquals(beforeMidnight + 1000, before.dailyResetAt());
        var midnight = AcademyRefreshPolicy.snapshot(player, beforeMidnight + 1000);
        assertEquals(0, midnight.dailyCount());
        assertEquals(30, midnight.roundCount());
        assertEquals(beforeMidnight + 3600_000L, midnight.refreshAt());
        assertEquals(100, player.getAcademyRefreshDailyCount(), "状态查询不写库");
    }

    @Test
    void aNewRoundStartsAtTheExactCooldownBoundaryWithoutResettingDailyCount() {
        long now = Instant.parse("2026-09-26T04:00:00Z").toEpochMilli();
        Player player = new Player();
        player.setAcademyRefreshDay(LocalDate.of(2026, 9, 26));
        player.setAcademyRefreshDailyCount(60);
        player.setAcademyRefreshRoundCount(30);
        player.setAcademyRefreshAt(now);
        assertEquals(30, AcademyRefreshPolicy.snapshot(player, now - 1).roundCount());
        var ready = AcademyRefreshPolicy.snapshot(player, now);
        assertEquals(0, ready.roundCount());
        assertEquals(0, ready.refreshAt());
        assertEquals(60, ready.dailyCount());
        AcademyRefreshPolicy.recordSuccess(player, ready, now);
        assertEquals(1, player.getAcademyRefreshRoundCount());
        assertEquals(61, player.getAcademyRefreshDailyCount());
        assertEquals(0, player.getAcademyRefreshAt());
    }
}
