package com.wargame.service.bot;

import com.wargame.util.GameClock;
import lombok.RequiredArgsConstructor;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BotScheduler {
    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
    private final BotStore store;
    private final BotService bots;
    @Value("${game.bot.enabled:true}") private boolean enabled = true;
    @Value("${game.bot.batch-size:10}") private int batchSize = 10;
    @Value("${game.scheduling.enabled:true}") private boolean schedulingEnabled = true;
    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public void ready() { ready = true; }

    @Scheduled(fixedDelayString = "${game.bot.poll-interval:5000}", scheduler = "botTaskScheduler")
    public void tick() {
        if (!ready || !enabled || !schedulingEnabled) return;
        for (Long playerId : store.due(GameClock.now(), Math.max(1, Math.min(batchSize, 50)))) {
            try { step(playerId, false); }
            catch (RuntimeException failure) { log.error("Bot {} failure could not be recorded", playerId, failure); }
        }
    }

    public BotService.Result step(long playerId, boolean force) {
        try {
            return bots.run(playerId, force);
        } catch (RuntimeException failure) {
            log.warn("Bot {} action rolled back: {}", playerId, failure.getMessage());
            return bots.failed(playerId, failure);
        }
    }
}
