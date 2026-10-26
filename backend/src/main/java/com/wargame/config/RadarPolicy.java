package com.wargame.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "game.radar")
public class RadarPolicy {
    private double radiusPerLevel = 1;
    private long reminderWindowMs = 60_000;
    private int pageSize = 50;

    public double radius(int level) {
        return Math.max(0, radiusPerLevel) * Math.max(0, Math.min(10, level));
    }
}
