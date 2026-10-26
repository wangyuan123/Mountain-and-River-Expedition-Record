package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 账号军备进度：部位整备、永久图鉴、任务去重与展示成就，分别使用有前缀的业务键。 */
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
