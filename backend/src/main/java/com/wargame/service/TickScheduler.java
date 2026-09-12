package com.wargame.service;

import com.wargame.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Bounded keyset batches: no findAll, no entity graph for players not being settled. */
@Component
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TickScheduler {
    private static final Logger log = LoggerFactory.getLogger(TickScheduler.class);
    private final PlayerRepository players;
    private final TickService ticks;
    private final long interval;
    private final int batchSize;
    private final int maxBatches;
    private long cursor;

    public TickScheduler(PlayerRepository players, TickService ticks,
                         @Value("${game.tick-interval:5000}") long interval,
                         @Value("${game.tick-batch-size:100}") int batchSize,
                         @Value("${game.tick-max-batches:20}") int maxBatches) {
        this.players = players;
        this.ticks = ticks;
        this.interval = Math.max(1, interval);
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
    }

    @Scheduled(fixedDelayString = "${game.tick-interval:5000}")
    public void tickAll() {
        long cutoff = System.currentTimeMillis() - interval;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<Long> ids = players.findDuePlayerIds(cursor, cutoff, PageRequest.of(0, batchSize));
            if (ids.isEmpty()) {
                cursor = 0;
                return;
            }
            for (Long id : ids) {
                cursor = id;
                try {
                    // Calls the transactional proxy; a conflict rolls back all settlement changes.
                    ticks.tick(id);
                } catch (OptimisticLockingFailureException e) {
                    log.debug("Player {} changed during settlement; retry on the next pass", id);
                } catch (Exception e) {
                    log.error("Tick failed for player {}", id, e);
                }
            }
            if (ids.size() < batchSize) {
                cursor = 0;
                return;
            }
        }
        // Keep the cursor so busy/failing low IDs cannot starve later players.
        log.debug("Tick batch budget reached; continue after player {} on the next pass", cursor);
    }
}
