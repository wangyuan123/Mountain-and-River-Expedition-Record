package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "buildings")
public class Building {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "level")
    private Integer level;

    /**
     * 多槽位建筑位次 (0-based). 由 V5 迁移添加, 配合生成列 is_slot0
     * 强制 (player_id, type, slot=0) 唯一, 允许同 type 多槽位共存.
     * 单槽位建筑固定为 0; 多槽位建筑由 BuildService 按 id 顺序分配.
     */
    @Column(name = "slot", nullable = false)
    private Integer slot = 0;
}
