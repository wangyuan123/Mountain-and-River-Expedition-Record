package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 独立演习持久化记录；不得用于真实世界结算。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "pvp_requests")
public class PvpRequest extends VersionedEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;
    @Column(name = "player_id")
    private Long playerId;
    @Column(name = "request_id", length = 64)
    private String requestId;
    @Column(name = "payload_hash", length = 64)
    private String hash;
    @Column(name = "response", columnDefinition = "longtext")
    private String response;
    @Column(name = "created_at", nullable = false)
    private long createdAt;
}
