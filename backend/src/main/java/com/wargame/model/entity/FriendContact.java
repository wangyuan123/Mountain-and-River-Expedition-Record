package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.*;

/** 好友关系为双方各保存一行，分组及黑名单由各账号独立管理。 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "friend_contacts", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "peer_id"}))
public class FriendContact {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(name = "peer_id", nullable = false) private Long peerId;
    @Column(name = "group_id") private Long groupId;
    @Column(name = "is_friend", nullable = false) private boolean friend;
    @Column(nullable = false) private boolean blocked;

    public FriendContact(Long ownerId, Long peerId) {
        this.ownerId = ownerId;
        this.peerId = peerId;
    }
}
