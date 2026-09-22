package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 两个军团之间共享的外交关系；军团 ID 始终按升序存储以保证唯一性。 */
@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "guild_relations")
public class GuildRelation extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guild_low_id", nullable = false)
    private Long guildLowId;

    @Column(name = "guild_high_id", nullable = false)
    private Long guildHighId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "updated_by_player_id", nullable = false)
    private Long updatedByPlayerId;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
