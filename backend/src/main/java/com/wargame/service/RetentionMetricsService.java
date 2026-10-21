package com.wargame.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 定期记录各类总量、过期积压和进程内增长趋势；盘点只读且独立于清理租约。 */
@Component
@RequiredArgsConstructor
public class RetentionMetricsService {
    private static final Logger log = LoggerFactory.getLogger(RetentionMetricsService.class);
    private static final Map<String, String> EXPIRY_COLUMNS = Map.ofEntries(
            Map.entry("chat_messages", "retention_expires_at"),
            Map.entry("guild_chat_messages", "retention_expires_at"),
            Map.entry("private_chat_messages", "retention_expires_at"),
            Map.entry("mails", "retention_expires_at"),
            Map.entry("scout_reports", "summary_expires_at"),
            Map.entry("shared_reports", "expires_at"),
            Map.entry("shared_incoming_reports", "expires_at"),
            Map.entry("pvp_practice_reports", "retention_expires_at"),
            Map.entry("pvp_practice_sessions", "retention_expires_at"),
            Map.entry("pvp_match_tickets", "retention_expires_at"),
            Map.entry("pvp_requests", "retention_expires_at"),
            Map.entry("mail_gift_activities", "cleanup_after"));
    private static final List<String> INVENTORY_TABLES = List.of(
            "chat_messages", "guild_chat_messages", "private_chat_messages", "mails", "scout_reports",
            "shared_reports", "shared_incoming_reports", "pvp_practice_reports", "pvp_practice_sessions",
            "pvp_match_tickets", "pvp_requests", "pvp_round_orders", "pvp_round_results",
            "mail_gift_activities", "mail_gift_deliveries", "forum_topics");
    private final JdbcTemplate jdbc;
    private final Map<String, Snapshot> previous = new ConcurrentHashMap<>();
    private record Snapshot(long count, long time) {}

    @Scheduled(fixedDelayString = "${game.retention.metrics-interval-ms:3600000}", initialDelay = 120_000, scheduler = "retentionMetricsTaskScheduler")
    public void collect() {
        long now = System.currentTimeMillis();
        try {
            String product = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName());
            boolean approximateCounts = product != null && (product.toLowerCase(Locale.ROOT).contains("mysql") ||
                    product.toLowerCase(Locale.ROOT).contains("mariadb"));
            for (String table : INVENTORY_TABLES) collectTable(table, now, approximateCounts);
            // 详情和摘要各有独立期限，分别记录积压最早到期点。
            recordBacklog("scout_reports.details", oldest("scout_reports", "detail_expires_at", now), now);
            logProtectedMailSources(now);
            logGiftTotals(now);
        } catch (RuntimeException e) {
            log.warn("Retention metrics unavailable: {}", e.getMessage());
        }
    }

    private void collectTable(String table, long now, boolean approximateCounts) {
        long rows;
        if (approximateCounts) {
            List<Map<String, Object>> estimate = jdbc.queryForList(
                    "SELECT TABLE_ROWS AS row_count FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?", table);
            if (estimate.isEmpty() || estimate.get(0).get("row_count") == null) return;
            rows = ((Number) estimate.get(0).get("row_count")).longValue();
        } else {
            rows = count(table);
        }
        String expiryColumn = EXPIRY_COLUMNS.get(table);
        Long oldestExpired = expiryColumn == null ? null : oldest(table, expiryColumn, now);
        record(table, rows, oldestExpired, now);
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private Long oldest(String table, String expiry, long now) {
        String eligible = "scout_reports".equals(table) ? " AND starred_at IS NULL AND " +
                ("detail_expires_at".equals(expiry) ? "data IS NOT NULL" : "data IS NULL AND summary_json IS NOT NULL") : "";
        return jdbc.query("SELECT MIN(" + expiry + ") FROM " + table + " WHERE " + expiry + "<=?" + eligible,
                rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null, now);
    }

    private void record(String table, long count, Long oldest, long now) {
        Snapshot before = previous.put(table, new Snapshot(count, now));
        String trend = before == null ? "n/a" : Long.toString(count - before.count());
        log.info("Retention inventory table={} rows={} changeSincePrevious={} oldestExpiredAt={}", table, count, trend, oldest);
        if (oldest != null) recordBacklog(table, oldest, now);
    }

    private void recordBacklog(String category, Long oldest, long now) {
        log.info("Retention backlog category={} oldestExpiredAt={}", category, oldest);
        if (oldest != null && now - oldest >= com.wargame.config.RetentionPolicy.DAY)
            log.warn("Retention backlog older than one day category={} oldestExpiredAt={}", category, oldest);
    }

    private void logProtectedMailSources(long now) {
        for (Map<String, Object> row : jdbc.queryForList("SELECT attachment_source_type,COUNT(*) AS source_count,MIN(persisted_real_at) AS oldest_at FROM mails WHERE attachment_policy='PROTECTED' AND is_claimed=FALSE GROUP BY attachment_source_type ORDER BY source_count DESC LIMIT 20")) {
            log.info("Retention protected mail source={} count={} oldestAt={}", row.get("attachment_source_type"), row.get("source_count"), row.get("oldest_at"));
        }
        Long unknown = jdbc.queryForObject("SELECT COUNT(*) FROM mails WHERE attachment_source_type='LEGACY_UNKNOWN' AND is_claimed=FALSE", Long.class);
        if (unknown != null && unknown > 0) log.warn("Retention unknown protected attachments={} sampledAt={}", unknown, now);
    }

    private void logGiftTotals(long now) {
        Map<String, Object> totals = jdbc.queryForMap("SELECT COUNT(*) AS issued, SUM(CASE WHEN is_claimed=TRUE THEN 1 ELSE 0 END) AS claimed, SUM(CASE WHEN is_claimed=FALSE AND attachment_policy='TIMED_GIFT' AND attachment_expires_at<=? THEN 1 ELSE 0 END) AS expired FROM mails WHERE attachment_source_type='LOGIN_ACTIVITY'", now);
        log.info("Retention gift activity totals sampledAt={} issued={} claimed={} expired={}", now, totals.get("issued"), totals.get("claimed"), totals.get("expired"));
    }
}
