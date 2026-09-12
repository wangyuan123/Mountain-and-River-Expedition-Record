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
@Table(name = "guild_members")
public class GuildMember extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "player_id", nullable = false, unique = true)
    private Long playerId;

    @Column(nullable = false, length = 16)
    private String role = "member";

    @Column(name = "joined_at", nullable = false)
    private Long joinedAt;
}
