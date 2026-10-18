package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.*;

/** 只保存待处理申请，处理后删除；唯一键和玩家锁共同防止重复申请。 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "friend_requests", uniqueConstraints = @UniqueConstraint(columnNames = {"sender_id", "recipient_id"}))
public class FriendRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "sender_id", nullable = false) private Long senderId;
    @Column(name = "recipient_id", nullable = false) private Long recipientId;
    @Column(name = "created_at", nullable = false) private Long createdAt;

    public FriendRequest(Long senderId, Long recipientId) {
        this.senderId = senderId; this.recipientId = recipientId; this.createdAt = System.currentTimeMillis();
    }
}
