package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tech_research_queue")
@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TechResearchQueue extends CityOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "tech_type", nullable = false, length = 50)
    private String techType;

    @Column(name = "target_level", nullable = false)
    private Integer targetLevel;

    @Column(name = "started_at", nullable = false)
    private Long startedAt;

    @Column(name = "finishes_at", nullable = false)
    private Long finishesAt;

    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds;

    @Column(name = "cost_food", nullable = false)
    private Integer costFood = 0;

    @Column(name = "cost_steel", nullable = false)
    private Integer costSteel = 0;

    @Column(name = "cost_oil", nullable = false)
    private Integer costOil = 0;

    @Column(name = "cost_rare", nullable = false)
    private Integer costRare = 0;

    @Column(name = "cost_gold", nullable = false)
    private Integer costGold = 0;
}
