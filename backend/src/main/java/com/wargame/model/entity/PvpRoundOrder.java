package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 独立演习持久化记录；不得用于真实世界结算。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "pvp_round_orders")
public class PvpRoundOrder extends VersionedEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;
    @Column(name = "session_id", length = 36)
    private String sessionId;
    @Column(name = "player_id")
    private Long playerId;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Column(name = "revision", nullable = false)
    private long revision;
    @Column(name = "orders", columnDefinition = "text")
    private String orders;
    @Column(name = "locked", nullable = false)
    private boolean locked;
    @Column(name = "locked_at", nullable = false)
    private long lockedAt;
    @Column(name = "source", length = 20)
    private String source;
    @Column(name = "request_id", length = 64)
    private String requestId;
}
