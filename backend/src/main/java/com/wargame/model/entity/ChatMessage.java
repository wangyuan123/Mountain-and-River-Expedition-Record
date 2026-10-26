package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@EntityListeners(com.wargame.service.RetentionEntityListener.class)
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "content", nullable = false, length = 512)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "retention_expires_at", nullable = false)
    private Long retentionExpiresAt;

    /** 兼容现有聊天发送调用，默认按世界聊天七天期限保存。 */
    public ChatMessage(Long id, Long playerId, String username, String content, Long createdAt) {
        this.id = id;
        this.playerId = playerId;
        this.username = username;
        this.content = content;
        this.createdAt = createdAt;
    }
}
