package com.wargame.model.constants;

import java.util.Map;

/**
 * 战斗声望规则：依据单位生产成本衡量战损的战略价值。
 * 钢铁、石油、稀矿分别按 1、2、4 的权重折算，每满 100 点折合 1 点声望价值。
 */
public final class BattlePrestige {

    private static final int STEEL_WEIGHT = 1;
    private static final int OIL_WEIGHT = 2;
    private static final int RARE_WEIGHT = 4;
    private static final int COST_PER_PRESTIGE = 100;

    private BattlePrestige() {
    }

    /**
     * 获取一个作战单位损失时对应的声望价值；城防和未知单位不参与计算。
     * @param unitId 单位类型
     * @return 单位的整数声望价值
     */
    public static int unitValue(String unitId) {
        UnitDef unit = GameData.UNITS.get(unitId);
        if (unit == null || unit.cost() == null) return 0;
        Map<String, Integer> cost = unit.cost();
        long weightedCost = (long) cost.getOrDefault("steel", 0) * STEEL_WEIGHT
                + (long) cost.getOrDefault("oil", 0) * OIL_WEIGHT
                + (long) cost.getOrDefault("rare", 0) * RARE_WEIGHT;
        return Math.max(1, (int) ((weightedCost + COST_PER_PRESTIGE - 1) / COST_PER_PRESTIGE));
    }

    /**
     * 计算一方在本次交锋中损失作战单位的总声望价值。
     * @param initial 战斗开始时的兵力
     * @param survivors 战斗结束后的幸存兵力
     * @return 该方损失的声望价值
     */
    public static int lossesValue(Map<String, Integer> initial, Map<String, Integer> survivors) {
        if (initial == null || initial.isEmpty()) return 0;
        long value = 0;
        for (Map.Entry<String, Integer> entry : initial.entrySet()) {
            int initialCount = Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
            int survivorCount = survivors == null ? 0 : Math.max(0, survivors.getOrDefault(entry.getKey(), 0));
            value += (long) Math.max(0, initialCount - survivorCount) * unitValue(entry.getKey());
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * 计算攻方的净声望变化，守方变化始终为相反数。
     * @param initialAttacker 攻方初始兵力
     * @param survivorAttacker 攻方幸存兵力
     * @param initialDefender 守方初始兵力
     * @param survivorDefender 守方幸存兵力
     * @return 攻方净声望变化
     */
    public static int attackerNetChange(Map<String, Integer> initialAttacker, Map<String, Integer> survivorAttacker,
                                        Map<String, Integer> initialDefender, Map<String, Integer> survivorDefender) {
        long defenderLossValue = lossesValue(initialDefender, survivorDefender);
        long attackerLossValue = lossesValue(initialAttacker, survivorAttacker);
        long net = defenderLossValue - attackerLossValue;
        if (net > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (net < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) net;
    }
}
