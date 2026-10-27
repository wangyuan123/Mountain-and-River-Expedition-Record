package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "player_power_snapshots", indexes = {
        @Index(name = "idx_power_player_time", columnList = "player_id,observed_at"),
        @Index(name = "idx_power_time", columnList = "observed_at")})
public class PlayerPowerSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "player_id", nullable = false)
    private Long playerId;
    @Column(name = "observed_at", nullable = false)
    private long observedAt;
    @Column(name = "formation_power", nullable = false)
    private long formationPower;
    @Column(name = "overall_power", nullable = false)
    private long overallPower;
}
