package com.wargame.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 游戏时钟独立于玩家结算；没有玩家在线时也正常运行。 */
@Component
@ConditionalOnProperty(name = "game.clock.enabled", havingValue = "true", matchIfMissing = true)
public class GameClockScheduler {
    private static final Logger log = LoggerFactory.getLogger(GameClockScheduler.class);
    private final GameClockRuntime clock;
    private final WebSocketPushService push;
    private volatile boolean ready;

    public GameClockScheduler(GameClockRuntime clock, WebSocketPushService push) {
        this.clock = clock;
        this.push = push;
    }

    /** 世界初始化和启动修复完成后才恢复游戏时间，启动耗时同样属于暂停期。 */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    public void ready() { clock.resume(); ready = true; }

    @Scheduled(fixedDelay = 1_000, scheduler = "gameClockTaskScheduler")
    public void pulse() {
        if (!ready) return;
        try {
            clock.advance();
        } catch (RuntimeException e) {
            log.error("游戏时钟检查点未提交，活动保持暂停", e);
        }
    }

    /** 慢客户端的网络发送不得阻塞游戏时钟落库。 */
    @Scheduled(fixedDelay = 1_000)
    public void publish() {
        if (ready) push.broadcast("clock", Map.of());
    }
}
