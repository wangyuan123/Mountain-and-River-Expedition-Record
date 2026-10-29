package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 账号内同兵种共享人口返还成本池；调城、出征、回城均不能重置成本。 */
@Entity
@Table(name = "army_population_balances", uniqueConstraints =
        @UniqueConstraint(name = "uq_army_population_player_type", columnNames = {"player_id", "unit_type"}))
@Data
@NoArgsConstructor
@lombok.EqualsAndHashCode(callSuper = false)
public class ArmyPopulationBalance extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "unit_type", nullable = false, length = 50) private String unitType;
    @Column(name = "reference_count", nullable = false) private long referenceCount;
    @Column(name = "refundable_population", nullable = false) private long refundablePopulation;
}
