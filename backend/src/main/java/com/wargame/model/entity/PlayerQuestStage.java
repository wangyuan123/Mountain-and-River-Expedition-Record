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
@Table(name = "player_quest_stages",
       uniqueConstraints = @UniqueConstraint(name = "uq_player_stage", columnNames = {"player_id", "stage_id"}))
public class PlayerQuestStage extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "stage_id", nullable = false, length = 32)
    private String stageId;

    /** in_progress / completed / claimed */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "completed_at")
    private Long completedAt;

    @Column(name = "claimed_at")
    private Long claimedAt;
}
