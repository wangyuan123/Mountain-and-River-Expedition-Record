package com.wargame.service.bot;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Bot 授权独立于账号标记；仅显式配置并启用的账号参与调度。 */
@Repository
@RequiredArgsConstructor
public class BotStore {
    private final JdbcTemplate jdbc;

    public record Settings(boolean enabled, boolean pvpEnabled, int citySlot, int intervalSeconds,
                           int reservePercent, int maxMarches, int armyTarget) {
        public Settings {
            if (citySlot < 0 || intervalSeconds < 15 || intervalSeconds > 3600
                    || reservePercent < 20 || reservePercent > 90 || maxMarches < 1 || maxMarches > 5
                    || armyTarget < 20 || armyTarget > 10000) {
                throw new IllegalArgumentException("Bot 参数超限：间隔15~3600秒、留守20~90%、行军1~5支、目标兵力20~10000");
            }
        }
    }

    public record Profile(long playerId, Settings settings, long nextActionAt, long actionCount,
                          int failureCount, String lastTarget, long targetCooldownUntil) {}

    public Optional<Profile> find(long playerId, boolean lock) {
        return jdbc.query("SELECT * FROM bot_profiles WHERE player_id = ?" + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new Profile(row.getLong("player_id"),
                        new Settings(row.getBoolean("enabled"), row.getBoolean("pvp_enabled"), row.getInt("city_slot"),
                                row.getInt("interval_seconds"), row.getInt("reserve_percent"), row.getInt("max_marches"), row.getInt("army_target")),
                        row.getLong("next_action_at"), row.getLong("action_count"), row.getInt("failure_count"),
                        row.getString("last_target"), row.getLong("target_cooldown_until")), playerId).stream().findFirst();
    }

    public List<Long> due(long now, int limit) {
        return jdbc.queryForList("SELECT player_id FROM bot_profiles WHERE enabled = TRUE AND next_action_at <= ? ORDER BY next_action_at, player_id LIMIT ?",
                Long.class, now, limit);
    }

    public void configure(long playerId, Settings settings, long now) {
        if (find(playerId, true).isEmpty()) jdbc.update("INSERT INTO bot_profiles (player_id) VALUES (?)", playerId);
        jdbc.update("UPDATE bot_profiles SET enabled=?, pvp_enabled=?, city_slot=?, interval_seconds=?, reserve_percent=?, max_marches=?, army_target=?, next_action_at=?, failure_count=0 WHERE player_id=?",
                settings.enabled(), settings.pvpEnabled(), settings.citySlot(), settings.intervalSeconds(), settings.reservePercent(),
                settings.maxMarches(), settings.armyTarget(), now, playerId);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT b.*, p.username, p.display_name FROM bot_profiles b JOIN players p ON p.id=b.player_id ORDER BY b.player_id");
    }

    public List<Map<String, Object>> logs(long playerId, int limit) {
        return jdbc.queryForList("SELECT * FROM bot_action_logs WHERE player_id=? ORDER BY id DESC LIMIT ?", playerId, Math.max(1, Math.min(limit, 200)));
    }

    /** 动作与下一次执行时刻随同业务操作提交，避免重启重放已扣款的决策。 */
    public void complete(Profile profile, BotAction action, boolean success, String reason, String detail, long now) {
        int failures = success ? 0 : Math.min(10, profile.failureCount() + 1);
        long delay = profile.settings().intervalSeconds() * 1000L * (success ? 1 : Math.min(8, failures));
        String target = action.targetId() == null ? profile.lastTarget() : action.subject() + ":" + action.targetId();
        long cooldown = action.targetId() == null ? profile.targetCooldownUntil()
                : now + (success && "SCOUT".equals(action.type()) ? 120_000 : success ? 600_000 : 300_000);
        jdbc.update("UPDATE bot_profiles SET next_action_at=?, action_count=action_count+1, failure_count=?, last_action=?, last_reason=?, last_target=?, target_cooldown_until=? WHERE player_id=?",
                now + delay, failures, action.type(), clip(reason), target, cooldown, profile.playerId());
        jdbc.update("INSERT INTO bot_action_logs(player_id,game_at,created_at,action,success,reason,detail) VALUES (?,?,?,?,?,?,?)",
                profile.playerId(), now, System.currentTimeMillis(), action.type(), success, clip(reason), detail);
        List<Long> cutoff = jdbc.queryForList("SELECT id FROM bot_action_logs WHERE player_id=? ORDER BY id DESC LIMIT 1 OFFSET 199", Long.class, profile.playerId());
        if (!cutoff.isEmpty()) jdbc.update("DELETE FROM bot_action_logs WHERE player_id=? AND id < ?", profile.playerId(), cutoff.get(0));
    }

    private String clip(String text) { return text == null ? "" : text.substring(0, Math.min(500, text.length())); }
}
