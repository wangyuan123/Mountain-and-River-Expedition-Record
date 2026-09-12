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
@Table(name = "constructions")
public class Construction extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "building_type", nullable = false, length = 50)
    private String buildingType;

    @Column(name = "target_level", nullable = false)
    private Integer targetLevel;

    @Column(name = "start_at")
    private Long startAt;

    @Column(name = "finish_at")
    private Long finishAt;

    @Column(name = "slot")
    private Integer slot;
}
