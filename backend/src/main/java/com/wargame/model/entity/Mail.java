package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 邮件实体。
 * <p>
 * 一行代表一封邮件，方向由 from_player_id / to_player_id 决定。
 * 玩家间互发时两边都各有一行？不，只有一行：from=A, to=B，B 在收件箱看到。
 * A 想看自己发出去的，发件箱通过 from_player_id=A 反查即可。
 * <p>
 * 系统邮件 from_player_id = NULL，但 from_name 仍然有展示名（"系统"/"战报中心" 等）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(com.wargame.service.RetentionEntityListener.class)
@Table(name = "mails")
public class Mail {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** Compatibility constructor for tests/importers created before surrender metadata. */
    public Mail(Long id, Long fromPlayerId, String fromName, Long toPlayerId, String toName,
                String type, boolean isSystem, String subject, String body, String attachJson,
                boolean isRead, boolean isClaimed, long createdAt) {
        this.id = id;
        this.fromPlayerId = fromPlayerId;
        this.fromName = fromName;
        this.toPlayerId = toPlayerId;
        this.toName = toName;
        this.type = type;
        this.subject = subject;
        this.isSystem = isSystem;
        this.body = body;
        this.attachJson = attachJson;
        this.isRead = isRead;
        this.isClaimed = isClaimed;
        this.createdAt = createdAt;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发件人玩家 ID；NULL 表示系统发件 */
    @Column(name = "from_player_id")
    private Long fromPlayerId;

    /** 发件人显示名（玩家统帅名或 "系统"/"战报中心"） */
    @Column(name = "from_name", nullable = false, length = 50)
    private String fromName;

    @Column(name = "to_player_id", nullable = false)
    private Long toPlayerId;

    @Column(name = "to_name", nullable = false, length = 50)
    private String toName;

    /** system / reward / alliance / combat / player */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = false;

    @Column(name = "subject", nullable = false, length = 80)
    private String subject;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** 附件 JSON: [{"type":"gold","qty":200}] */
    @Column(name = "attach_json", columnDefinition = "TEXT")
    private String attachJson;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "is_claimed", nullable = false)
    private Boolean isClaimed = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    /** Optional interactive diplomatic offer referenced by this mail. */
    @Column(name = "surrender_offer_id")
    private Long surrenderOfferId;

    @Column(name = "persisted_real_at", nullable = false)
    private Long persistedRealAt;

    @Column(name = "processed_real_at")
    private Long processedRealAt;

    /** 创建时保存期限快照，配置变更不追溯影响已存在邮件。 */
    @Column(name = "processed_retention_ms", nullable = false)
    private long processedRetentionMs;
    @Column(name = "unread_retention_ms", nullable = false)
    private long unreadRetentionMs;

    @Column(name = "retention_expires_at")
    private Long retentionExpiresAt;

    @Column(name = "attachment_policy", nullable = false, length = 20)
    private String attachmentPolicy = "PROTECTED";

    @Column(name = "attachment_source_type", nullable = false, length = 40)
    private String attachmentSourceType = "LEGACY_UNKNOWN";

    @Column(name = "attachment_source_id", length = 100)
    private String attachmentSourceId;

    @Column(name = "attachment_expires_at")
    private Long attachmentExpiresAt;

    @Column(name = "attachment_expired_at")
    private Long attachmentExpiredAt;

    @Column(name = "cleanup_after")
    private Long cleanupAfter;

    /** 新邮件缺省将未识别附件作为受保护资产；只有显式限时赠礼策略允许过期。 */
    public void initializeRetention(com.wargame.config.RetentionPolicy policy, long now) {
        if (persistedRealAt == null) persistedRealAt = now;
        if (processedRetentionMs <= 0) processedRetentionMs = policy.days(policy.getProcessedMailDays());
        if (unreadRetentionMs <= 0) unreadRetentionMs = policy.days(policy.getUnreadMailDays());
        boolean hasAttachment = hasPendingAttachment();
        if (!hasAttachment || Boolean.TRUE.equals(isClaimed)) {
            attachmentPolicy = "NONE";
            attachmentSourceType = attachmentSourceType == null ? "NONE" : attachmentSourceType;
            if (Boolean.TRUE.equals(isRead) && surrenderOfferId == null) {
                processedRealAt = processedRealAt == null ? now : processedRealAt;
            }
        } else if (attachmentPolicy == null || attachmentPolicy.isBlank()) {
            attachmentPolicy = "PROTECTED";
        }
        if (surrenderOfferId != null) return;
        if ("TIMED_GIFT".equals(attachmentPolicy) && attachmentExpiresAt != null) {
            if (cleanupAfter == null) cleanupAfter = attachmentExpiresAt + policy.days(policy.getGiftNoticeDays());
            if (retentionExpiresAt == null) retentionExpiresAt = cleanupAfter;
        } else if (Boolean.TRUE.equals(isRead) && (!hasAttachment || Boolean.TRUE.equals(isClaimed))) {
            if (retentionExpiresAt == null) retentionExpiresAt = (processedRealAt == null ? now : processedRealAt) + processedRetentionMs;
        } else if (!hasAttachment || Boolean.TRUE.equals(isClaimed)) {
            if (retentionExpiresAt == null) retentionExpiresAt = persistedRealAt + unreadRetentionMs;
        }
    }

    /** 畸形或未知附件仍按资产保护；只有可信的空值和空数组表示没有待领附件。 */
    public boolean hasPendingAttachment() {
        if (Boolean.TRUE.equals(isClaimed) || attachmentExpiredAt != null) return false;
        return attachJson != null && !attachJson.isBlank() && !"[]".equals(attachJson.trim()) && !"null".equals(attachJson.trim());
    }
}
