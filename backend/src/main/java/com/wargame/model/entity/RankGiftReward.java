package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;

/** 每账号每阶永久回执；钻石按发放时快照兑现，不随后续商城调价变化。 */
@Data
@Entity
@Table(name = "rank_gift_rewards", uniqueConstraints = @UniqueConstraint(
        name = "uq_rank_gift_player_tier", columnNames = {"player_id", "rank_tier"}))
public class RankGiftReward extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "rank_tier", nullable = false) private int rankTier;
    @Column(name = "item_key", nullable = false, length = 40) private String itemKey;
    @Column(nullable = false) private int diamonds;
    @Column(nullable = false, length = 20) private String source;
    @Column(name = "issued_at", nullable = false) private long issuedAt;
    @Column(name = "opened_at") private Long openedAt;
}
