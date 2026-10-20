package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "army_production_queue")
@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArmyProductionQueue extends CityOwnedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "player_id", nullable = false)
    private Long playerId;
    @Column(name = "unit_type", nullable = false, length = 50)
    private String unitType;
    @Column(name = "building_type", length = 50)
    private String buildingType;
    @Column(name = "unit_count", nullable = false)
    private Integer unitCount;
    @Column(name = "started_at", nullable = false)
    private Long startedAt;
    @Column(name = "finishes_at", nullable = false)
    private Long finishesAt;
    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds;
    @Column(name = "speed_multiplier", nullable = false, columnDefinition = "DECIMAL(10,3)")
    private Double speedMultiplier;
    @Column(name = "cost_food", nullable = false)
    private Integer costFood = 0;
    @Column(name = "cost_steel", nullable = false)
    private Integer costSteel = 0;
    @Column(name = "cost_oil", nullable = false)
    private Integer costOil = 0;
    @Column(name = "cost_rare", nullable = false)
    private Integer costRare = 0;
    // 可空以兼容历史付费订单；完工按原订单兑现，不使用新规则追溯检查。
    @Column(name = "unlock_rule_version", length = 64) private String unlockRuleVersion;
    @Column(name = "unlock_requirements", columnDefinition = "text") private String unlockRequirements;
}
