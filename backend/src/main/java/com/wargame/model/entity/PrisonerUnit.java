package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 战俘按战斗、原所有者及兵种保存批次；稳定城市ID防止槽位复用串城。 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "prisoner_units", uniqueConstraints = @UniqueConstraint(columnNames = {"settlement_id", "source_id", "type"}))
public class PrisonerUnit extends CityOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "city_id", nullable = false) private Long cityId;
    @Column(name = "settlement_id", nullable = false) private Long settlementId;
    @Column(name = "source_id", nullable = false, length = 80) private String sourceId;
    @Column(name = "original_owner_id", nullable = false) private Long originalOwnerId;
    @Column(nullable = false, length = 50) private String type;
    @Column(name = "original_count", nullable = false) private Integer originalCount;
    @Column(nullable = false) private Integer count;
    @Column(name = "created_at", nullable = false) private Long createdAt;
}
