package com.wargame.model.dto;

import com.wargame.util.JsonUtil;
import lombok.Data;
import java.util.*;

/** 本场战斗的技能进度；随回合事务保存，不从实时军官或其他战斗恢复。 */
@Data
public class BattleSkillState {
    private Army attacker = new Army();
    private Army defender = new Army();

    @Data
    public static class Army {
        private int round;
        private Map<String, Integer> initial = new LinkedHashMap<>();
        private Map<String, String> sources = new LinkedHashMap<>();
        private Map<String, Integer> opening = new LinkedHashMap<>();
        private Map<String, Unit> units = new LinkedHashMap<>();
        private List<Map<String, Object>> events = new ArrayList<>();
        public Unit unit(String id) { return units.computeIfAbsent(id, ignored -> new Unit()); }
    }

    @Data
    public static class Unit {
        private String focus;
        private int focusRound, focusCount, attackRound;
        private double rangingRate, pursuitAttackRate;
        private int barrierRound;
        private double barrier;
        private Map<Integer, Double> received = new LinkedHashMap<>();
        private int revengeRound, smokeRound, pursuitRound;
        private double smokeRate;
    }

    /** 历史会话从冻结的开战兵力初始化；坏快照必须报错，不能静默重置技能进度。 */
    public static BattleSkillState read(String json) {
        if (json == null || json.isBlank()) return new BattleSkillState();
        try { return JsonUtil.getMapper().readValue(json, BattleSkillState.class); }
        catch (Exception error) { throw new IllegalStateException("技能快照损坏", error); }
    }
}
