package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 账号军备进度：永久图鉴、展示成就和账号方案；历史整备及材料任务键只保留审计，不再生效。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "officer_equipment_progress", uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "progress_key"}))
public class OfficerEquipmentProgress extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "progress_key", nullable = false, length = 120) private String progressKey;
    @Column(nullable = false) private int amount;
}
