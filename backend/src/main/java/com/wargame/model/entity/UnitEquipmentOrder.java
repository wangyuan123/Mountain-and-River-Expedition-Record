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
}
