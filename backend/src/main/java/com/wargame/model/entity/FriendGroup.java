package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.*;

/** 仅保存自定义分组；“我的好友”为固定的默认分组，使用空 groupId。 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "friend_groups", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "name"}))
public class FriendGroup {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(nullable = false, length = 20) private String name;

    public FriendGroup(Long ownerId, String name) { this.ownerId = ownerId; this.name = name; }
}
