package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_quests",
       uniqueConstraints = @UniqueConstraint(name = "uq_player_quest", columnNames = {"player_id", "quest_id"}))
public class PlayerQuest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "quest_id", nullable = false, length = 64)
    private String questId;

    /** locked / in_progress / completed / claimed */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "progress", nullable = false)
    private Integer progress;

    @Column(name = "target_value", nullable = false)
    private Integer targetValue;

    @Column(name = "completed_at")
    private Long completedAt;

    @Column(name = "claimed_at")
    private Long claimedAt;
}
