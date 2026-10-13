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

    /** 攻守各自城市的司令官属性快照，不能随玩家切城、换任或换装变化。 */
    @Column(name = "attacker_command_bonus", columnDefinition = "text")
    private String attackerCommandBonus;
    @Column(name = "defender_command_bonus", columnDefinition = "text")
    private String defenderCommandBonus;

    /** 实际参战的玩家城指挥官；0表示无将参战，null兼容历史会话。 */
    @Column(name = "defender_commander_id")
    private Long defenderCommanderId;
    /** 本场实际装备配置组的派兵人；历史战报不重新读取驻军或账号配装。 */
    @Column(name = "defender_equipment_sources", columnDefinition = "longtext")
    private String defenderEquipmentSources;

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

    /** 双方首次实际开火的回合；0 表示尚未交战，历史会话的 null 从战报恢复。 */
    @Column(name = "first_combat_round")
    private Integer firstCombatRound = 0;

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
    /** 开战时各军团驻军实际参战数量，迟到的援军不承担本场损失。 */
    @Column(name = "defender_support", columnDefinition = "longtext")
    private String defenderSupport;
    @Column(name = "attacker_positions", columnDefinition = "text")
    private String attackerPositions;
    @Column(name = "defender_positions", columnDefinition = "text")
    private String defenderPositions;
    /** 本场战斗的玩家指令持续生效，直到该方再次修改；未下令的兵种沿用账号预设。 */
    @Column(name = "attacker_orders", columnDefinition = "text")
    private String attackerOrders;
    @Column(name = "defender_orders", columnDefinition = "text")
    private String defenderOrders;
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
