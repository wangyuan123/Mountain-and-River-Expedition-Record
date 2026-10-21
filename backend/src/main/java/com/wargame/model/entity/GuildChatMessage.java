package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@EntityListeners(com.wargame.service.RetentionEntityListener.class)
@Table(name = "guild_chat_messages")
public class GuildChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "content", nullable = false, length = 80)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "retention_expires_at", nullable = false)
    private Long retentionExpiresAt;

    /** 兼容现有军团聊天发送调用，默认按三十天期限保存。 */
    public GuildChatMessage(Long id, Long guildId, Long playerId, String username, String content, Long createdAt) {
        this.id = id;
        this.guildId = guildId;
        this.playerId = playerId;
        this.username = username;
        this.content = content;
        this.createdAt = createdAt;
    }
}
