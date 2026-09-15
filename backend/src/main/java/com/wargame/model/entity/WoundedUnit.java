package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 每场战斗独立保存，后来的伤兵不会延长旧伤兵的救治期限。 */
@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@Entity
@Table(name = "wounded_units")
public class WoundedUnit extends CityOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "city_id") private Long cityId;
    @Column(name = "city_x", nullable = false) private Integer cityX;
    @Column(name = "city_y", nullable = false) private Integer cityY;
    @Column(name = "city_name", nullable = false) private String cityName;
    @Column(name = "type", nullable = false, length = 50) private String type;
    @Column(name = "count", nullable = false) private Integer count;
    @Column(name = "created_at", nullable = false) private Long createdAt;
    @Column(name = "expires_at", nullable = false) private Long expiresAt;
    @Column(name = "recovery_percent", nullable = false) private Integer recoveryPercent;
}
