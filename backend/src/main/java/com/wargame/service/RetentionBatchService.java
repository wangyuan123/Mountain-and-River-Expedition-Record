package com.wargame.service;

import com.wargame.config.RetentionPolicy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 每批独立事务，持有控制行锁，提交前租约无法被其他实例接管。 */
@Service
@RequiredArgsConstructor
public class RetentionBatchService {
    private static final Logger log = LoggerFactory.getLogger(RetentionBatchService.class);
    public static final int BATCH_SIZE = 200;
    public static final List<String> CATEGORIES = List.of("summaries", "chat", "guild", "private", "mailState", "mails", "details", "reports", "shares", "incoming", "pvpReports", "pvpSessions", "pvpTickets", "pvpRequests", "activities");
    private final JdbcTemplate jdbc;
    private final MailService mails;
    private final RetentionPolicy policy;
    public record Result(int candidates, int changed, int protectedCount, int invalid, boolean cleanup) {}

    /** 每批重新验证开关、七天观察期、持有者和截止时间；SQL 使用事务超时。 */
    @Transactional(timeout = 2)
    public Result execute(String owner, String category, long deadlineNanos) {
        var controls = jdbc.queryForList("SELECT * FROM data_retention_control WHERE control_id=1 AND lease_owner=? FOR UPDATE", owner);
        if (controls.isEmpty() || System.nanoTime() >= deadlineNanos) return new Result(0, 0, 0, 0, false);
        long now = System.currentTimeMillis();
        var c = controls.get(0);
        if (number(c, "lease_until") <= now) return new Result(0, 0, 0, 0, false);
        boolean enabled = Boolean.TRUE.equals(jdbc.queryForObject("SELECT cleanup_enabled FROM data_retention_control WHERE control_id=1", Boolean.class));
        boolean cleanup = enabled && now - number(c, "observation_started_at") >= 7 * RetentionPolicy.DAY;
        jdbc.update("UPDATE data_retention_control SET lease_until=?,next_category=? WHERE control_id=1 AND lease_owner=?", now + 30_000, (CATEGORIES.indexOf(category) + 1) % CATEGORIES.size(), owner);
        if ("summaries".equals(category)) return backfill(c, deadlineNanos);
        if ("details".equals(category)) return trim(c, now, cleanup, deadlineNanos);
        String selection = switch (category) {
            case "chat" -> "SELECT id FROM chat_messages WHERE retention_expires_at<=? ORDER BY retention_expires_at,id LIMIT 200";
            case "guild" -> "SELECT id FROM guild_chat_messages WHERE retention_expires_at<=? ORDER BY retention_expires_at,id LIMIT 200";
            case "private" -> "SELECT id FROM private_chat_messages WHERE retention_expires_at<=? ORDER BY retention_expires_at,id LIMIT 200";
            case "mailState" -> "SELECT m.id FROM mails m LEFT JOIN city_surrender_offers o ON o.id=m.surrender_offer_id WHERE (o.id IS NULL OR o.status NOT IN ('PENDING','ACCEPTED')) AND ((m.surrender_offer_id IS NOT NULL AND m.retention_expires_at IS NULL AND (m.is_claimed=TRUE OR m.attach_json IS NULL OR TRIM(m.attach_json) IN ('','[]','null'))) OR (m.attachment_policy='TIMED_GIFT' AND m.is_claimed=FALSE AND m.attachment_expires_at<=? AND m.attachment_expired_at IS NULL)) ORDER BY m.id LIMIT 200";
            case "mails" -> "SELECT m.id FROM mails m WHERE m.retention_expires_at<=? AND NOT EXISTS(SELECT 1 FROM city_surrender_offers o WHERE o.id=m.surrender_offer_id AND o.status IN ('PENDING','ACCEPTED')) AND (m.is_claimed=TRUE OR m.attachment_expired_at IS NOT NULL OR m.attach_json IS NULL OR TRIM(m.attach_json) IN ('','[]','null')) ORDER BY m.retention_expires_at,m.id LIMIT 200";
            case "reports" -> "SELECT id FROM scout_reports WHERE summary_expires_at<=? AND starred_at IS NULL AND data IS NULL AND summary_json IS NOT NULL ORDER BY summary_expires_at,id LIMIT 200";
            case "shares" -> "SELECT token FROM shared_reports WHERE expires_at<=? ORDER BY expires_at,token LIMIT 200";
            case "incoming" -> "SELECT token FROM shared_incoming_reports WHERE expires_at<=? ORDER BY expires_at,token LIMIT 200";
            case "pvpReports" -> "SELECT r.id FROM pvp_practice_reports r WHERE r.retention_expires_at<=? OR (SELECT COUNT(*) FROM pvp_practice_reports n WHERE n.player_id=r.player_id AND n.retention_expires_at>" + now + " AND (n.created_at>r.created_at OR (n.created_at=r.created_at AND n.id>r.id)))>=50 ORDER BY r.persisted_real_at,r.id LIMIT 200";
            case "pvpSessions" -> "SELECT s.id FROM pvp_practice_sessions s WHERE s.status IN ('FINISHED','VOID') AND s.retention_expires_at<=? AND NOT EXISTS(SELECT 1 FROM pvp_practice_reports r WHERE r.session_id=s.id) AND NOT EXISTS(SELECT 1 FROM pvp_player_states p WHERE p.active_session_id=s.id OR p.active_ticket_id IN (SELECT t.id FROM pvp_match_tickets t WHERE t.session_id=s.id)) AND NOT EXISTS(SELECT 1 FROM pvp_match_tickets t WHERE t.session_id=s.id AND t.status IN ('QUEUED','ACCEPTING')) ORDER BY s.retention_expires_at,s.id LIMIT 200";
            case "pvpTickets" -> "SELECT t.id FROM pvp_match_tickets t WHERE t.retention_expires_at<=? AND t.session_id IS NULL AND t.status IN ('CLOSED','EXPIRED','CANCELLED') AND NOT EXISTS(SELECT 1 FROM pvp_player_states p WHERE p.active_ticket_id=t.id) ORDER BY t.retention_expires_at,t.id LIMIT 200";
            case "pvpRequests" -> "SELECT r.id FROM pvp_requests r WHERE r.retention_expires_at<=? AND NOT EXISTS(SELECT 1 FROM pvp_player_states p WHERE p.player_id=r.player_id AND (p.active_session_id IS NOT NULL OR p.active_ticket_id IS NOT NULL)) AND NOT EXISTS(SELECT 1 FROM pvp_practice_sessions s WHERE (s.player_a=r.player_id OR s.player_b=r.player_id) AND (s.settled_real_at IS NULL OR s.retention_expires_at>" + now + ")) ORDER BY r.retention_expires_at,r.id LIMIT 200";
            case "activities" -> "SELECT a.id FROM mail_gift_activities a WHERE a.closed=TRUE AND a.cleanup_after<=? AND NOT EXISTS(SELECT 1 FROM mails m WHERE m.attachment_source_type='LOGIN_ACTIVITY' AND m.attachment_source_id=a.id AND m.is_claimed=FALSE AND (m.attachment_policy<>'TIMED_GIFT' OR m.attachment_expires_at>" + now + " OR m.surrender_offer_id IS NOT NULL)) ORDER BY a.cleanup_after,a.id LIMIT 200";
            default -> throw new IllegalArgumentException("未知清理类别");
        };
        long cutoff = now;
        List<String> ids = jdbc.query(selection, (rs, n) -> rs.getString(1), cutoff);
        int changed = 0, protectedCount = 0;
        for (String id : ids) {
            if (System.nanoTime() >= deadlineNanos || changed >= BATCH_SIZE) break;
            // 观察期允许补齐期限元数据，但禁止删除或裁剪正文。
            if (!cleanup && !"mailState".equals(category)) {
                if ("pvpSessions".equals(category) && jdbc.queryForObject("SELECT COUNT(*) FROM pvp_player_states WHERE active_session_id=?", Integer.class, id) > 0) protectedCount++;
                continue;
            }
            int count = mutate(category, id, now, cutoff, BATCH_SIZE - changed);
            changed += count;
            if (count == 0) protectedCount++;
        }
        if ("mails".equals(category)) log.info("Retention protected mail sample={}", jdbc.queryForList("SELECT attachment_source_type,COUNT(*) AS sampled_count,MIN(created_at) AS oldest_at FROM (SELECT attachment_source_type,created_at FROM mails WHERE attachment_policy='PROTECTED' AND is_claimed=FALSE ORDER BY id LIMIT 200) protected_sample GROUP BY attachment_source_type"));
        return new Result(ids.size(), changed, protectedCount, 0, cleanup);
    }

    private int mutate(String category, String id, long now, long cutoff, int remaining) {
        return switch (category) {
            case "chat", "guild", "private" -> jdbc.update("DELETE FROM " + Map.of("chat", "chat_messages", "guild", "guild_chat_messages", "private", "private_chat_messages").get(category) + " WHERE id=? AND retention_expires_at<=?", id, now);
            case "mailState" -> mails.refreshRetention(Long.parseLong(id), now) ? 1 : 0;
            case "mails" -> mails.deleteExpired(Long.parseLong(id), now) ? 1 : 0;
            case "reports" -> deleteReport(Long.parseLong(id), now, remaining);
            case "shares" -> jdbc.update("DELETE FROM shared_reports WHERE token=? AND expires_at<=?", id, now);
            case "incoming" -> jdbc.update("DELETE FROM shared_incoming_reports WHERE token=? AND expires_at<=?", id, now);
            case "pvpReports" -> deletePvpReport(id, now);
            case "pvpSessions" -> deleteSession(id, cutoff, remaining);
            case "pvpTickets" -> deleteTicket(id, cutoff);
            case "pvpRequests" -> deleteRequest(id, cutoff);
            case "activities" -> deleteActivity(id, now, cutoff, remaining);
            default -> 0;
        };
    }

    private Result backfill(Map<String, Object> c, long deadline) {
        var rows = jdbc.queryForList("SELECT id,data,summary_json FROM scout_reports WHERE id>? AND list_summary_json IS NULL AND (data IS NOT NULL OR summary_json IS NOT NULL) ORDER BY id LIMIT 200", number(c, "backfill_cursor"));
        int changed = 0, invalid = 0; long cursor = 0;
        for (var row : rows) {
            if (System.nanoTime() >= deadline) break;
            cursor = number(row, "id");
            String data = (String) row.get("data");
            String summary = row.get("summary_json") == null ? ReportSummary.fromJson(data) : (String) row.get("summary_json");
            String card = ReportSummary.cardJson(summary);
            if (summary == null || card == null) { invalid++; log.warn("Retention invalid summary id={}", cursor); }
            else changed += jdbc.update("UPDATE scout_reports SET summary_json=COALESCE(summary_json,?),list_summary_json=?,summary_schema_version=1 WHERE id=? AND list_summary_json IS NULL", summary, card, cursor);
        }
        jdbc.update("UPDATE data_retention_control SET backfill_cursor=? WHERE control_id=1", cursor);
        return new Result(rows.size(), changed, 0, invalid, false);
    }

    private Result trim(Map<String, Object> c, long now, boolean cleanup, long deadline) {
        var ids = jdbc.query("SELECT id FROM scout_reports WHERE id>? AND detail_expires_at<=? AND starred_at IS NULL AND data IS NOT NULL ORDER BY id LIMIT 200", (rs, n) -> rs.getLong(1), number(c, "detail_cursor"), now);
        int changed = 0, invalid = 0, protectedCount = 0; long cursor = 0;
        for (Long id : ids) {
            if (System.nanoTime() >= deadline) break;
            cursor = id;
            var rows = jdbc.queryForList("SELECT data,summary_json,type,starred_at,detail_expires_at FROM scout_reports WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) continue;
            var r = rows.get(0);
            if (r.get("starred_at") != null || number(r, "detail_expires_at") > now) { protectedCount++; continue; }
            if (!ReportSummary.canTrim((String) r.get("data"), (String) r.get("summary_json"), (String) r.get("type"))) { invalid++; log.warn("Retention keeps unverified historical report detail id={}", id); continue; }
            if (cleanup) changed += jdbc.update("UPDATE scout_reports SET data=NULL WHERE id=? AND starred_at IS NULL AND detail_expires_at<=?", id, now);
        }
        // 完成一轮后回到起点；解除保护或修复过的记录仍会再次接受检查。
        jdbc.update("UPDATE data_retention_control SET detail_cursor=? WHERE control_id=1", cursor);
        return new Result(ids.size(), changed, protectedCount, invalid, cleanup);
    }

    private int deleteReport(long id, long now, int remaining) {
        if (jdbc.queryForList("SELECT id FROM scout_reports WHERE id=? AND starred_at IS NULL AND data IS NULL AND summary_json IS NOT NULL AND summary_expires_at<=? FOR UPDATE", id, now).isEmpty()) return 0;
        int deleted = jdbc.update("DELETE FROM shared_reports WHERE report_id=? LIMIT " + remaining, id);
        if (deleted >= remaining || jdbc.queryForObject("SELECT COUNT(*) FROM shared_reports WHERE report_id=?", Integer.class, id) > 0) return deleted;
        return deleted + jdbc.update("DELETE FROM scout_reports WHERE id=?", id);
    }

    private int deletePvpReport(String id, long now) {
        var rows = jdbc.queryForList("SELECT player_id FROM pvp_practice_reports WHERE id=?", id);
        if (rows.isEmpty()) return 0;
        lockPlayers(List.of(number(rows.get(0), "player_id")));
        int due = jdbc.queryForObject("SELECT COUNT(*) FROM pvp_practice_reports r WHERE id=? AND (retention_expires_at<=? OR (SELECT COUNT(*) FROM pvp_practice_reports n WHERE n.player_id=r.player_id AND n.retention_expires_at>? AND (n.created_at>r.created_at OR (n.created_at=r.created_at AND n.id>r.id)))>=50)", Integer.class, id, now, now);
        return due == 0 ? 0 : jdbc.update("DELETE FROM pvp_practice_reports WHERE id=?", id);
    }

    private void lockPlayers(List<Long> ids) {
        for (long id : ids.stream().distinct().sorted().toList()) {
            jdbc.queryForList("SELECT player_id FROM pvp_player_states WHERE player_id=? FOR UPDATE", id);
        }
    }

    private int deleteSession(String id, long cutoff, int remaining) {
        var peek = jdbc.queryForList("SELECT player_a,player_b FROM pvp_practice_sessions WHERE id=?", id);
        if (peek.isEmpty()) return 0;
        lockPlayers(List.of(number(peek.get(0), "player_a"), number(peek.get(0), "player_b")));
        if (jdbc.queryForList("SELECT id FROM pvp_practice_sessions s WHERE id=? AND status IN ('FINISHED','VOID') AND settled_real_at IS NOT NULL AND retention_expires_at<=? AND NOT EXISTS(SELECT 1 FROM pvp_practice_reports r WHERE r.session_id=s.id) AND NOT EXISTS(SELECT 1 FROM pvp_player_states p WHERE p.active_session_id=s.id OR p.active_ticket_id IN (SELECT t.id FROM pvp_match_tickets t WHERE t.session_id=s.id)) AND NOT EXISTS(SELECT 1 FROM pvp_match_tickets t WHERE t.session_id=s.id AND t.status IN ('QUEUED','ACCEPTING')) FOR UPDATE", id, cutoff).isEmpty()) return 0;
        // 锁顺序与演习服务一致：按玩家 ID 锁状态，再锁会话；双方均无报告后才能删回合。
        jdbc.update("UPDATE pvp_player_states SET last_session_id=NULL,version=version+1 WHERE last_session_id=?", id);
        int deleted = 0;
        for (String table : List.of("pvp_round_orders", "pvp_round_results", "pvp_match_tickets")) {
            deleted += jdbc.update("DELETE FROM " + table + " WHERE session_id=? LIMIT " + (remaining - deleted), id);
            if (deleted >= remaining) return deleted;
        }
        return deleted + jdbc.update("DELETE FROM pvp_practice_sessions WHERE id=?", id);
    }

    private int deleteTicket(String id, long cutoff) {
        var rows = jdbc.queryForList("SELECT player_id FROM pvp_match_tickets WHERE id=?", id);
        if (rows.isEmpty()) return 0;
        lockPlayers(List.of(number(rows.get(0), "player_id")));
        return jdbc.update("DELETE FROM pvp_match_tickets WHERE id=? AND retention_expires_at<=? AND session_id IS NULL AND status IN ('CLOSED','EXPIRED','CANCELLED') AND NOT EXISTS(SELECT 1 FROM pvp_player_states WHERE active_ticket_id=?)", id, cutoff, id);
    }

    private int deleteRequest(String id, long cutoff) {
        var rows = jdbc.queryForList("SELECT player_id FROM pvp_requests WHERE id=?", id);
        if (rows.isEmpty()) return 0;
        long player = number(rows.get(0), "player_id");
        lockPlayers(List.of(player));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM pvp_player_states WHERE player_id=? AND (active_session_id IS NOT NULL OR active_ticket_id IS NOT NULL)", Integer.class, player) > 0) return 0;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM pvp_practice_sessions WHERE (player_a=? OR player_b=?) AND (settled_real_at IS NULL OR retention_expires_at>?)", Integer.class, player, player, cutoff) > 0) return 0;
        return jdbc.update("DELETE FROM pvp_requests WHERE id=? AND retention_expires_at<=?", id, cutoff);
    }

    private int deleteActivity(String id, long now, long cutoff, int remaining) {
        if (jdbc.queryForList("SELECT id FROM mail_gift_activities a WHERE id=? AND closed=TRUE AND cleanup_after<=? AND NOT EXISTS(SELECT 1 FROM mails m WHERE m.attachment_source_type='LOGIN_ACTIVITY' AND m.attachment_source_id=a.id AND m.is_claimed=FALSE AND (m.attachment_policy<>'TIMED_GIFT' OR m.attachment_expires_at>? OR m.surrender_offer_id IS NOT NULL)) FOR UPDATE", id, cutoff, now).isEmpty()) return 0;
        var ids = jdbc.query("SELECT id FROM mail_gift_deliveries WHERE activity_id=? ORDER BY id LIMIT " + remaining, (rs, n) -> rs.getLong(1), id);
        for (Long delivery : ids) jdbc.update("DELETE FROM mail_gift_deliveries WHERE id=?", delivery);
        return ids.isEmpty() ? jdbc.update("DELETE FROM mail_gift_activities WHERE id=?", id) : ids.size();
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }
}
