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

    /** 攻守双方在本次战斗中损失的可治疗兵力（不含城防）。 */
    private int attackerLosses;
    private int defenderLosses;

    /** 战斗结束时锁定的伤兵回收率及进入伤兵营的人数。 */
    private int attackerRecoveryPercent;
    private int defenderRecoveryPercent;
    private int attackerRecoveredCount;
    private int defenderRecoveredCount;

    /** 战斗结算产生的声望、民心变化及结算后的数值。 */
    private int attackerPrestigeChange;
    private int defenderPrestigeChange;
    private int attackerPrestigeAfter;
    private int defenderPrestigeAfter;
    private int attackerMoraleChange;
    private int defenderMoraleChange;
    private int attackerMoraleAfter;
    private int defenderMoraleAfter;

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
