package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;

/** 战术会话清理后保留双方终局快照，迟到请求不能重新结算或读取他人的战果。 */
@Data
@Entity
@Table(name = "tactical_battle_outcomes")
public class TacticalBattleOutcome {
    @Id private Long marchId;
    @Column(nullable = false) private Long attackerId;
    private Long defenderId;
    @Column(nullable = false, columnDefinition = "longtext") private String attackerView;
    @Column(columnDefinition = "longtext") private String defenderView;
    @Column(nullable = false) private Long expiresAt;
}
