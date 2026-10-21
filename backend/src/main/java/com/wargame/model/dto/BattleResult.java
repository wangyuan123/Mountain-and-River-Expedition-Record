package com.wargame.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 战斗结果 DTO - 对应 JS Battle.finish / resolveWild 的返回结果
 */
@Data
@NoArgsConstructor
public class BattleResult {

    /** 独立墙体终局供战报回放，不能混入部队损失统计。 */
    private ConquestWallState wall;
    private String terminalReason;

    /** 城外拦截开局规则快照，双方战报共享；旧报告没有此字段。 */
    private Map<String, Object> interceptionOpening;

    /** 司令官开战快照，与实际带兵将领独立记录。 */
    private Map<String, Object> commandBonuses;

    /** 双方实际参战的装备物品、数值和规则版本快照。 */
    private Map<String, Object> unitEquipment;

    /** 攻方是否获胜 */
    private boolean win;

    /** 攻方幸存单位 (unitType -> count) */
    private Map<String, Integer> survivorAttacker;

    /** 守方幸存单位 (unitType -> count, 含城防) */
    private Map<String, Integer> survivorDefender;

    /** 攻方初始参战单位 (unitType -> count) */
    private Map<String, Integer> initialAttacker;

    /** 守方初始参战单位 (unitType -> count, 含城防) */
    private Map<String, Integer> initialDefender;

    /** 掠夺到的资源 (food/steel/oil/rare/gold -> amount) */
    private Map<String, Integer> plunderedResources;

    /** 获得的经验值 */
    private int expGained;

    /** 战斗报告文本 */
    private String report;

    /** 城市是否被征服 */
    private boolean cityConquered;

    /** 玩家城未接管原因；战斗获胜并不必然取得城权。 */
    private String cityTakeoverReason;

    /** 野地胜利后未占领的原因快照，如出发城市名额已满。 */
    private String wildOccupationReason;

    /** 实际转籍的守城指挥官；为空时不得显示俘虏或累计俘虏任务。 */
    private Long capturedOfficerId;
    private String capturedOfficerName;

    /** 攻守双方在本次战斗中损失的可治疗兵力（不含城防）。 */
    private int attackerLosses;
    private int defenderLosses;

    /** 战斗结束时锁定的伤兵回收率及进入伤兵营的人数。 */
    private int attackerRecoveryPercent;
    private int defenderRecoveryPercent;
    private int attackerRecoveredCount;
    private int defenderRecoveredCount;

    /** 逐兵种伤兵和战俘快照；历史战报字段缺失时继续采用原显示。 */
    private Map<String, Integer> attackerWounded;
    private Map<String, Integer> defenderWounded;
    private Map<String, Object> prisoners;

    /** 战斗结算产生的声望、民心变化及结算后的数值。 */
    private int attackerPrestigeChange;
    private int defenderPrestigeChange;
    private int attackerPrestigeAfter;
    private int defenderPrestigeAfter;
    /** 民心仅对已结算的玩家城市记录；未参战城市或 NPC 保持为空，不能当作民心为 0。 */
    private Integer attackerMoraleChange;
    private Integer defenderMoraleChange;
    private Integer attackerMoraleAfter;
    private Integer defenderMoraleAfter;

    /** 民怨与民心按同一参战城市冻结；未结算的城市保持为空，零增量也须记录。 */
    private Integer attackerResentmentChange;
    private Integer defenderResentmentChange;
    private Integer attackerResentmentAfter;
    private Integer defenderResentmentAfter;

    public BattleResult(boolean win, Map<String, Integer> survivorAttacker, Map<String, Integer> survivorDefender,
                        Map<String, Integer> initialAttacker, Map<String, Integer> initialDefender,
                        Map<String, Integer> plunderedResources, int expGained, String report, boolean cityConquered) {
        this.win = win;
        this.survivorAttacker = survivorAttacker;
        this.survivorDefender = survivorDefender;
        this.initialAttacker = initialAttacker;
        this.initialDefender = initialDefender;
        this.plunderedResources = plunderedResources;
        this.expGained = expGained;
        this.report = report;
        this.cityConquered = cityConquered;
    }

    public BattleResult(boolean win, Map<String, Integer> survivorAttacker, Map<String, Integer> survivorDefender,
                        Map<String, Integer> plunderedResources, int expGained, String report, boolean cityConquered) {
        this(win, survivorAttacker, survivorDefender, null, null, plunderedResources, expGained, report, cityConquered);
    }
}
