package com.wargame.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class WoundedExpiryScheduler {
    private final WoundedService wounded;
    public WoundedExpiryScheduler(WoundedService wounded) { this.wounded = wounded; }
    @Scheduled(fixedDelay = 60000)
    public void expire() { wounded.expireWounded(); }
}
