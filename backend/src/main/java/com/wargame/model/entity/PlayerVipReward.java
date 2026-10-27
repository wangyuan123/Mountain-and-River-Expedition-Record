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
@Table(name = "player_vip_rewards", uniqueConstraints = {
        @UniqueConstraint(name = "uk_player_vip_level", columnNames = {"player_id", "vip_level"})
})
public class PlayerVipReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "vip_level", nullable = false)
    private Integer vipLevel;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private LocalDateTime claimedAt = LocalDateTime.now();

    public PlayerVipReward(Long playerId, Integer vipLevel) {
        this.playerId = playerId;
        this.vipLevel = vipLevel;
        this.claimedAt = LocalDateTime.now();
    }
}
