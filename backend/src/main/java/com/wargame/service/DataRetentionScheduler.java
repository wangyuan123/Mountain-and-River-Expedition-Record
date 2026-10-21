package com.wargame.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** 按持久化轮转位置推进，失败批次独立回滚，不阻断其他类别。 */
@Component
@RequiredArgsConstructor
public class DataRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);
    private final JdbcTemplate jdbc;
    private final RetentionBatchService batches;

    @Scheduled(fixedDelayString = "${game.retention.interval-ms:600000}", initialDelay = 60_000, scheduler = "retentionTaskScheduler")
    public void run() {
        long now = System.currentTimeMillis();
        String owner = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + 5_000_000_000L;
        try {
            if (jdbc.update("UPDATE data_retention_control SET lease_owner=?,lease_until=?,last_run_at=? WHERE control_id=1 AND lease_until<?", owner, now + 30_000, now, now) == 0) return;
            int start = jdbc.queryForObject("SELECT next_category FROM data_retention_control WHERE control_id=1 AND lease_owner=?", Integer.class, owner);
            int size = RetentionBatchService.CATEGORIES.size();
            boolean[] done = new boolean[size];
            // 每类最多五批，即一千个候选；总预算耗尽后从下一类别继续。
            for (int round = 0; round < 5 && System.nanoTime() < deadline; round++) {
                for (int offset = 0; offset < size && System.nanoTime() < deadline; offset++) {
                    int index = (start + offset) % size;
                    if (done[index]) continue;
                    String category = RetentionBatchService.CATEGORIES.get(index);
                    long began = System.nanoTime();
                    try {
                        var r = batches.execute(owner, category, deadline);
                        done[index] = !r.cleanup() || r.candidates() < RetentionBatchService.BATCH_SIZE || r.changed() == 0;
                        log.info("Retention category={} cleanup={} candidates={} changed={} protected={} invalid={} elapsedMs={}", category, r.cleanup(), r.candidates(), r.changed(), r.protectedCount(), r.invalid(), (System.nanoTime() - began) / 1_000_000);
                    } catch (RuntimeException e) {
                        done[index] = true;
                        jdbc.update("UPDATE data_retention_control SET next_category=? WHERE control_id=1 AND lease_owner=?", (index + 1) % size, owner);
                        log.warn("Retention category={} failed; next round retries: {}", category, e.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) { log.warn("Retention round unavailable: {}", e.getMessage()); }
        finally {
            try { jdbc.update("UPDATE data_retention_control SET lease_owner=NULL,lease_until=0 WHERE control_id=1 AND lease_owner=?", owner); }
            catch (RuntimeException e) { log.warn("Retention lease release deferred until expiry: {}", e.getMessage()); }
        }
    }
}
