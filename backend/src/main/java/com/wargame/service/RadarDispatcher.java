package com.wargame.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.TreeSet;

@Component
@RequiredArgsConstructor
public class RadarDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RadarDispatcher.class);
    private final RadarService radar;
    private final RadarDeliveryService delivery;
    @org.springframework.beans.factory.annotation.Value("${game.scheduling.enabled:true}") private boolean scheduling;

    @Scheduled(fixedDelayString = "${game.radar.recovery-interval-ms:5000}")
    public void recover() { if (scheduling) refresh(); }

    public void refresh() {
        refresh(Set.of());
    }

    public void refresh(Set<Long> affected) {
        Set<Long> owners = new TreeSet<>(radar.monitoredPlayers());
        owners.addAll(affected);
        for (Long owner : owners) {
            try {
                delivery.refresh(owner);
                delivery.deliver(owner);
            } catch (RuntimeException failure) {
                log.warn("Radar update deferred for player {}: {}", owner, failure.getMessage());
            }
        }
    }
}
