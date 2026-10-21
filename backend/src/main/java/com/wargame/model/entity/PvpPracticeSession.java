package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 独立演习持久化记录；不得用于真实世界结算。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@EntityListeners(com.wargame.service.RetentionEntityListener.class)
@Table(name = "pvp_practice_sessions")
public class PvpPracticeSession extends VersionedEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;
    @Column(name = "player_a")
    private Long playerA;
    @Column(name = "player_b")
    private Long playerB;
    @Column(name = "ticket_a", length = 36)
    private String ticketA;
    @Column(name = "ticket_b", length = 36)
    private String ticketB;
    @Column(name = "snapshot_a", columnDefinition = "longtext")
    private String snapshotA;
    @Column(name = "snapshot_b", columnDefinition = "longtext")
    private String snapshotB;
    @Column(name = "status", length = 20)
    private String status;
    @Column(name = "deadline_at", nullable = false)
    private long deadlineAt;
    @Column(name = "starts_at", nullable = false)
    private long startsAt;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Column(name = "loaded_a", nullable = false)
    private boolean loadedA;
    @Column(name = "loaded_b", nullable = false)
    private boolean loadedB;
    @Column(name = "seed", nullable = false)
    private long seed;
    @Column(name = "rules_version", length = 64)
    private String rulesVersion;
    @Column(name = "battle_data", columnDefinition = "longtext")
    private String battleData;
    @Column(name = "result", length = 20)
    private String result;
    @Column(name = "reason", length = 100)
    private String reason;
    @Column(name = "settled_at", nullable = false)
    private long settledAt;
    @Column(name = "created_at", nullable = false)
    private long createdAt;
    @Column(name = "settled_real_at")
    private Long settledRealAt;
    @Column(name = "history_retention_ms", nullable = false)
    private long historyRetentionMs;
    @Column(name = "retention_expires_at")
    private Long retentionExpiresAt;

    /** 结算才启动真实时间期限，使用创建会话时保存的配置。 */
    public void setSettledRealAt(Long value) {
        settledRealAt = value;
        if (value != null && historyRetentionMs > 0) retentionExpiresAt = value + historyRetentionMs;
    }
}
