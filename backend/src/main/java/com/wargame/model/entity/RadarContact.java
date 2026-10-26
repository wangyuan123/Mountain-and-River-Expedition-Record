package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@lombok.EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "radar_contacts", uniqueConstraints = @UniqueConstraint(columnNames = {"observer_id", "march_id"}),
        indexes = @Index(name = "idx_radar_observer_time", columnList = "observer_id,first_seen_at"))
public class RadarContact extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "observer_id", nullable = false)
    private Long observerId;
    @Column(name = "march_id", nullable = false)
    private Long marchId;
    @Column(name = "guild_id", nullable = false)
    private Long guildId;
    @Column(name = "attacker_id", nullable = false)
    private Long attackerId;
    @Column(name = "target_key", nullable = false, length = 160)
    private String targetKey;
    @Column(name = "threat_kind", nullable = false, length = 16)
    private String threatKind;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "first_seen_at", nullable = false)
    private long firstSeenAt;
    @Column(name = "last_seen_at", nullable = false)
    private long lastSeenAt;
    @Column(name = "read_at", nullable = false)
    private long readAt;
    @Column(name = "notice_pending", nullable = false)
    private boolean noticePending;
    @Column(name = "notice_at", nullable = false)
    private long noticeAt;
    @Column(name = "reminder_group", nullable = false, length = 36)
    private String reminderGroup;
    @Column(name = "snapshot", nullable = false, columnDefinition = "longtext")
    private String snapshot;
    @Column(name = "sources", nullable = false, columnDefinition = "text")
    private String sources;
    @Column(name = "expires_at", nullable = false)
    private long expiresAt;
}
