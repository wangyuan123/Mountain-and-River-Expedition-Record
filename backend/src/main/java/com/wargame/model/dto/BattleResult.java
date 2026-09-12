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

    public BattleResult() {}

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

    public boolean isWin() { return win; }
    public void setWin(boolean win) { this.win = win; }

    public Map<String, Integer> getSurvivorAttacker() { return survivorAttacker; }
    public void setSurvivorAttacker(Map<String, Integer> survivorAttacker) { this.survivorAttacker = survivorAttacker; }

    public Map<String, Integer> getSurvivorDefender() { return survivorDefender; }
    public void setSurvivorDefender(Map<String, Integer> survivorDefender) { this.survivorDefender = survivorDefender; }

    public Map<String, Integer> getInitialAttacker() { return initialAttacker; }
    public void setInitialAttacker(Map<String, Integer> initialAttacker) { this.initialAttacker = initialAttacker; }

    public Map<String, Integer> getInitialDefender() { return initialDefender; }
    public void setInitialDefender(Map<String, Integer> initialDefender) { this.initialDefender = initialDefender; }

    public Map<String, Integer> getPlunderedResources() { return plunderedResources; }
    public void setPlunderedResources(Map<String, Integer> plunderedResources) { this.plunderedResources = plunderedResources; }

    public int getExpGained() { return expGained; }
    public void setExpGained(int expGained) { this.expGained = expGained; }

    public String getReport() { return report; }
    public void setReport(String report) { this.report = report; }

    public boolean isCityConquered() { return cityConquered; }
    public void setCityConquered(boolean cityConquered) { this.cityConquered = cityConquered; }
}
