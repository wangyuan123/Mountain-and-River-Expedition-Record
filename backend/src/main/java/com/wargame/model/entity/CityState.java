package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "city_state")
public class CityState extends CityOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "war_end_at")
    private Long warEndAt;

    @Column(name = "shield_until")
    private Long shieldUntil;

    @Column(name = "peace_until")
    private Long peaceUntil;

    @Column(name = "march_boost_until")
    private Long marchBoostUntil;

    @Column(name = "march_boost_percent")
    private Integer marchBoostPercent = 50;

    /** 旧存档未记录档位时沿用原来的 50%；过期效果不参与任何出征速度计算。 */
    public double marchSpeedMultiplier(long now) {
        if (marchBoostUntil == null || marchBoostUntil <= now) return 1.0;
        return 1.0 + (marchBoostPercent == null ? 50 : marchBoostPercent) / 100.0;
    }

}
