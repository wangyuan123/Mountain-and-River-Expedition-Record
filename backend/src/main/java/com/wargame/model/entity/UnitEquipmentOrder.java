package com.wargame.model.entity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
/** 城市独立制造通道，费用与时间在下单时冻结。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "unit_equipment_orders")
public class UnitEquipmentOrder extends CityOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "item_key", nullable = false, length = 50) private String itemKey;
    @Column(name = "cost", nullable = false, columnDefinition = "text") private String cost;
    @Column(name = "start_at", nullable = false) private Long startAt;
    @Column(name = "end_at", nullable = false) private Long endAt;
    // 可空以兼容历史付费订单；完工按原订单兑现，不使用新规则追溯检查。
    @Column(name = "unlock_rule_version", length = 64) private String unlockRuleVersion;
    @Column(name = "unlock_requirements", columnDefinition = "text") private String unlockRequirements;
}
