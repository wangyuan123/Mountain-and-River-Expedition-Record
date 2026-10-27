package com.wargame.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class PlayerPowerSnapshotScheduler {
    private final PlayerPowerService power;
    private final PlayerPowerHistoryService history;

    /** 定时覆盖离线账号及后台变更，采样与清理均参与同一写事务。 */
    @Scheduled(fixedDelayString = "${game.war-power.snapshot-interval-ms:300000}", initialDelay = 30_000)
    @Transactional
    public void sample() {
        power.calculate(null);
        history.prune();
    }
}
