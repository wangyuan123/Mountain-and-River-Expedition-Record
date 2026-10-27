package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_vip_milestones", uniqueConstraints = {
        @UniqueConstraint(name = "uk_player_milestone", columnNames = {"player_id", "milestone_cents"})
})
public class PlayerVipMilestone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "milestone_cents", nullable = false)
    private Long milestoneCents;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private LocalDateTime claimedAt = LocalDateTime.now();

    public PlayerVipMilestone(Long playerId, Long milestoneCents) {
        this.playerId = playerId;
        this.milestoneCents = milestoneCents;
        this.claimedAt = LocalDateTime.now();
    }
}
