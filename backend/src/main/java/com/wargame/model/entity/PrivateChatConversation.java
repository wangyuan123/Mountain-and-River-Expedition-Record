package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.*;

/** 玩家主动打开的会话，即使尚未发送消息也持久保留。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "private_chat_conversations", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "peer_id"}))
public class PrivateChatConversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(name = "peer_id", nullable = false) private Long peerId;
    @Column(nullable = false) private Long openedAt;
    @Column(nullable = false) private Long deletedThrough = 0L;
    public PrivateChatConversation(Long id, Long ownerId, Long peerId, Long openedAt) {
        this.id = id; this.ownerId = ownerId; this.peerId = peerId; this.openedAt = openedAt;
    }
}
