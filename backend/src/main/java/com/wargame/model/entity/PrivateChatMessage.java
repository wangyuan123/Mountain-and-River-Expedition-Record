package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(com.wargame.service.RetentionEntityListener.class)
@Table(name = "private_chat_messages")
public class PrivateChatMessage {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long senderId;
    @Column(nullable = false) private Long recipientId;
    @Column(nullable = false, length = 80) private String content;
    @Column(nullable = false) private Long createdAt;
    @Column(nullable = false) private boolean readByRecipient;
    @Column(name = "retention_expires_at", nullable = false) private Long retentionExpiresAt;

    /** 兼容既有私聊调用并设置统一九十天保留期。 */
    public PrivateChatMessage(Long id, Long senderId, Long recipientId, String content, Long createdAt, boolean readByRecipient) {
        this.id = id;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.content = content;
        this.createdAt = createdAt;
        this.readByRecipient = readByRecipient;
    }

    @PrePersist
    private void initializeRetention() {
        long now = System.currentTimeMillis();
        if (retentionExpiresAt == null) retentionExpiresAt = Math.max(now, createdAt == null ? now : createdAt) + 90 * DAY_MS;
    }
}
