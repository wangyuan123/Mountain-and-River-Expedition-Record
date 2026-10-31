package com.wargame.model.dto.pvp;
import lombok.Data;
import java.util.Map;

/** 所有可变战斗数据只属于虚拟会话；倒计时另存索引列以供重启恢复。 */
@Data
public class PvpBattleData {
    private Map<String, Integer> armyA, armyB, positionsA, positionsB;
    private com.wargame.model.dto.BattleSkillState skillState = new com.wargame.model.dto.BattleSkillState();
    private int distance, firstCombatRound;
    private boolean autoA, autoB, pendingTakeoverA, pendingTakeoverB;
    private double lossA, lossB;
}
