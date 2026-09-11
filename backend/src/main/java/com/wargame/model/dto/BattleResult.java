package com.wargame.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 战斗结果 DTO - 对应 JS Battle.finish / resolveWild 的返回结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BattleResult {

    /** 攻方是否获胜 */
    private boolean win;

    /** 攻方幸存单位 (unitType -> count) */
    private Map<String, Integer> survivorAttacker;

    /** 守方幸存单位 (unitType -> count, 含城防) */
    private Map<String, Integer> survivorDefender;

    /** 掠夺到的资源 (food/steel/oil/rare/gold -> amount) */
    private Map<String, Integer> plunderedResources;

    /** 获得的经验值 */
    private int expGained;

    /** 战斗报告文本 */
    private String report;

    /** 城市是否被征服 */
    private boolean cityConquered;
}
