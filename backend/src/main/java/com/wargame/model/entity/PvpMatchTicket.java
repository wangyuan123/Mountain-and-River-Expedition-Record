package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 独立演习持久化记录；不得用于真实世界结算。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@EntityListeners(com.wargame.service.RetentionEntityListener.class)
@Table(name = "pvp_match_tickets")
public class PvpMatchTicket extends VersionedEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;
    @Column(name = "player_id")
    private Long playerId;
    @Column(name = "region", length = 40)
    private String region;
    @Column(name = "battlefield", length = 20)
    private String battlefield;
    @Column(name = "snapshot", columnDefinition = "longtext")
    private String snapshot;
    @Column(name = "snapshot_hash", length = 64)
    private String snapshotHash;
    @Column(name = "rules_version", length = 64)
    private String rulesVersion;
    @Column(name = "power_model_version", length = 80)
    private String powerModelVersion;
    @Column(name = "power", nullable = false)
    private long power;
    @Column(name = "rating", nullable = false)
    private int rating;
    @Column(name = "placement", nullable = false)
    private boolean placement;
    @Column(name = "queued_at", nullable = false)
    private long queuedAt;
    @Column(name = "lease_until", nullable = false)
    private long leaseUntil;
    @Column(name = "status", length = 20)
    private String status;
    @Column(name = "session_id", length = 36)
    private String sessionId;
    @Column(name = "accepted", nullable = false)
    private boolean accepted;
    @Column(name = "persisted_real_at", nullable = false)
    private long persistedRealAt;
    @Column(name = "retention_expires_at", nullable = false)
    private long retentionExpiresAt;

    @PrePersist
    private void initializeRealTime() {
        if (persistedRealAt <= 0) persistedRealAt = System.currentTimeMillis();
    }
}
