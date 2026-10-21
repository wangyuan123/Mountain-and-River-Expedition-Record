package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 独立演习持久化记录；不得用于真实世界结算。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@EntityListeners(com.wargame.service.RetentionEntityListener.class)
@Table(name = "pvp_practice_reports")
public class PvpPracticeReport extends VersionedEntity {
    private static final long THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000;
    @Id
    @Column(name = "id", length = 36)
    private String id;
    @Column(name = "session_id", length = 36)
    private String sessionId;
    @Column(name = "player_id")
    private Long playerId;
    @Column(name = "created_at", nullable = false)
    private long createdAt;
    @Column(name = "result", length = 20)
    private String result;
    @Column(name = "data", columnDefinition = "longtext")
    private String data;
    @Column(name = "persisted_real_at", nullable = false)
    private long persistedRealAt;
    @Column(name = "retention_expires_at", nullable = false)
    private long retentionExpiresAt;

    @PrePersist
    private void initializeRetention() {
        long now = System.currentTimeMillis();
        if (persistedRealAt <= 0) persistedRealAt = now;
        if (retentionExpiresAt <= 0) retentionExpiresAt = persistedRealAt + THIRTY_DAYS_MS;
    }
}
