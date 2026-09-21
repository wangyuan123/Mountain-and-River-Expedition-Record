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
@Table(name = "officer_equipment")
public class OfficerEquipment extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "officer_id") private Long officerId;
    @Column(name = "item_key", nullable = false, length = 80) private String itemKey;
    @Column(name = "set_type", nullable = false, length = 20) private String setType;
    @Column(name = "tier", nullable = false) private Integer tier;
    @Column(name = "slot", nullable = false, length = 20) private String slot;
    @Column(name = "military_bonus", nullable = false) private Integer militaryBonus;
    @Column(name = "defense_bonus", nullable = false) private Integer defenseBonus = 0;
    @Column(name = "logistics_bonus", nullable = false) private Integer logisticsBonus;
    @Column(name = "knowledge_bonus", nullable = false) private Integer knowledgeBonus;
    @Column(name = "created_at", nullable = false) private Long createdAt;
    @Column(name = "equipped_at", nullable = false) private Long equippedAt;
}
