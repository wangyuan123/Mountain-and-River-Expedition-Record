package com.wargame.service;

import com.wargame.config.GameWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 通过数据库会话版本感知其他实例的注销，关闭未发生读写的空闲连接。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AccountSessionScheduler {
    private final GameWebSocketHandler sockets;
    @Scheduled(fixedDelay = 1000)
    public void checkSessions() { sockets.closeInvalidSessions(); }
}
