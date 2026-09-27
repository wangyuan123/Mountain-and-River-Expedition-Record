package com.wargame.service;

import com.wargame.model.entity.Player;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** 军校每轮30次、用满冷却一小时；每日100次按北京时间统计。 */
public final class AcademyRefreshPolicy {
    public static final int ROUND_LIMIT = 30;
    public static final int DAILY_LIMIT = 100;
    public static final long COOLDOWN_MILLIS = 3600_000L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private AcademyRefreshPolicy() {}

    /** 只计算当前有效额度，不在查询状态时写库；跨日只重置日计数。 */
    public static Snapshot snapshot(Player player, long now) {
        LocalDate day = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate();
        long refreshAt = player.getAcademyRefreshAt();
        int roundCount = player.getAcademyRefreshRoundCount();
        if (refreshAt > 0 && refreshAt <= now) {
            refreshAt = 0;
            roundCount = 0;
        }
        int dailyCount = day.equals(player.getAcademyRefreshDay()) ? player.getAcademyRefreshDailyCount() : 0;
        long dailyResetAt = day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        return new Snapshot(roundCount, dailyCount, refreshAt, day, dailyResetAt);
    }

    /** 必须在持有玩家行锁、资源扣费成功的同一事务中调用，失败请求不占次数。 */
    public static void recordSuccess(Player player, Snapshot before, long now) {
        int roundCount = before.roundCount() + 1;
        player.setAcademyRefreshRoundCount(roundCount);
        player.setAcademyRefreshDailyCount(before.dailyCount() + 1);
        player.setAcademyRefreshDay(before.day());
        player.setAcademyRefreshAt(roundCount == ROUND_LIMIT ? now + COOLDOWN_MILLIS : 0L);
    }

    public record Snapshot(int roundCount, int dailyCount, long refreshAt, LocalDate day, long dailyResetAt) {
        public Map<String, Object> toState() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("refreshRoundCount", roundCount);
            state.put("refreshRoundLimit", ROUND_LIMIT);
            state.put("refreshDailyCount", dailyCount);
            state.put("refreshDailyLimit", DAILY_LIMIT);
            state.put("refreshAt", refreshAt);
            state.put("refreshDailyResetAt", dailyResetAt);
            return state;
        }
    }
}
