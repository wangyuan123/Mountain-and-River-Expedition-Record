package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 持久化的逐回合战斗状态，防止页面刷新或后台 Tick 自动跳过玩家指挥。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "battle_sessions")
public class BattleSession extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "march_id", nullable = false, unique = true)
    private Long marchId;

    @Column(name = "target_kind", length = 50)
    private String targetKind;

    @Column(name = "target_id", length = 100)
    private String targetId;

    @Column(name = "target_name", length = 100)
    private String targetName;

    @Column(name = "action", length = 50)
    private String action;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "round_deadline_at", nullable = false)
    private Long roundDeadlineAt;

    @Column(name = "initial_distance", nullable = false)
    private Integer initialDistance;

    @Column(name = "attacker_army", columnDefinition = "text")
    private String attackerArmy;
    @Column(name = "defender_army", columnDefinition = "text")
    private String defenderArmy;
    @Column(name = "initial_attacker", columnDefinition = "text")
    private String initialAttacker;
    @Column(name = "initial_defender", columnDefinition = "text")
    private String initialDefender;
    @Column(name = "attacker_positions", columnDefinition = "text")
    private String attackerPositions;
    @Column(name = "defender_positions", columnDefinition = "text")
    private String defenderPositions;
    @Column(name = "attacker_tech", columnDefinition = "text")
    private String attackerTech;
    @Column(name = "defender_tech", columnDefinition = "text")
    private String defenderTech;
    @Column(name = "attacker_skills", columnDefinition = "text")
    private String attackerSkills;
    @Column(name = "defender_skills", columnDefinition = "text")
    private String defenderSkills;
    @Column(name = "defender_resources", columnDefinition = "text")
    private String defenderResources;
    @Column(name = "battle_log", columnDefinition = "longtext")
    private String battleLog;

    @Column(name = "attacker_commander_mil", nullable = false)
    private Integer attackerCommanderMil;
    @Column(name = "attacker_commander_def", nullable = false)
    private Integer attackerCommanderDef;
    @Column(name = "defender_commander_mil", nullable = false)
    private Integer defenderCommanderMil;
    @Column(name = "defender_commander_def", nullable = false)
    private Integer defenderCommanderDef;
    @Column(name = "defender_wall_level", nullable = false)
    private Integer defenderWallLevel;
    @Column(name = "defender_warehouse_level", nullable = false)
    private Long defenderWarehouseLevel;
}
