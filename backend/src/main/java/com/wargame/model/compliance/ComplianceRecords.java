package com.wargame.model.compliance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 身份信息与公开玩家资料分表，不生成包含敏感字段的 toString。 */
public final class ComplianceRecords {
    private ComplianceRecords() {}
    @Entity(name = "IdentitySubject") @Table(name = "identity_subjects") @Getter @Setter
    public static class Subject {
        @Id @Column(length = 64) private String id;
        @Column(nullable = false, length = 128) private String birthCipher;
        @Column(nullable = false) private long verifiedUntil;
        @Column(length = 64) private String guardianId;
        @Column(length = 80) private String consentVersion;
        @Column(nullable = false) private int dailyLimitSeconds = 3600;
        @Column(nullable = false) private int endMinute = 1260;
        @Column(nullable = false) private boolean paused;
        @Column(nullable = false) private boolean chatAllowed = true;
        @Column(length = 64) private String activeSession;
        @Column(nullable = false) private long updatedAt;
        @Version private long version;
    }
    @Entity(name = "PlayerIdentity") @Table(name = "player_identities", indexes = @Index(name = "idx_identity_subject", columnList = "subject_id")) @Getter @Setter
    public static class Binding {
        @Id private Long playerId;
        @Column(nullable = false, length = 64) private String subjectId;
        @Column(nullable = false) private long verifiedAt;
    }
    @Entity(name = "PlaySession") @Table(name = "play_sessions", indexes = @Index(name = "idx_play_player", columnList = "player_id")) @Getter @Setter
    public static class Session {
        @Id @Column(length = 64) private String id;
        @Column(nullable = false, length = 64) private String subjectId;
        @Column(nullable = false) private Long playerId;
        @Column(nullable = false) private long authVersion;
        @Column(nullable = false) private long startedAt;
        @Column(nullable = false) private long accountedThrough;
        @Column(nullable = false) private long leaseUntil;
        @Column(nullable = false) private long allowedUntil;
        @Column(nullable = false) private long endedAt;
        @Version private long version;
    }
    @Entity(name = "PlayUsage") @Table(name = "play_usage_daily") @Getter @Setter
    public static class Usage {
        @Id @Column(length = 80) private String id;
        @Column(nullable = false, length = 64) private String subjectId;
        @Column(nullable = false, length = 10) private String playDate;
        @Column(nullable = false) private long usedMillis;
        @Version private long version;
    }
    @Entity(name = "ComplianceEvent") @Table(name = "compliance_events", indexes = @Index(name = "idx_compliance_event_time", columnList = "created_at")) @Getter @Setter
    public static class Event {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
        private Long playerId;
        @Column(nullable = false, length = 40) private String eventType;
        @Column(nullable = false, length = 80) private String policyVersion;
        @Column(nullable = false) private long createdAt;
    }
    @Entity(name = "ProtectionRequest") @Table(name = "protection_requests", indexes = @Index(name = "idx_protection_player", columnList = "player_id")) @Getter @Setter
    public static class SupportRequest {
        @Id @Column(length = 36) private String id;
        @Column(nullable = false) private Long playerId;
        @Column(nullable = false, length = 24) private String requestType;
        @Column(nullable = false, length = 24) private String status = "RECEIVED";
        @Column(nullable = false) private long createdAt;
    }
}
