package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "guilds")
public class Guild extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16)
    private String name;

    @Column(nullable = false, length = 200)
    private String notice = "";

    @Column(nullable = false, length = 32)
    private String icon = "⚑";

    @Column(name = "leader_player_id", nullable = false)
    private Long leaderPlayerId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
