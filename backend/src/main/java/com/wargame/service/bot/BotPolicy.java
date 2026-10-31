package com.wargame.service.bot;

import com.wargame.model.constants.*;
import java.util.*;

/** 可重复验证的保守策略：留守、限制出兵，未知守军和未知工事均不视为空军。 */
public final class BotPolicy {
    private BotPolicy() {}

    public static Map<String, Integer> expedition(Map<String, Integer> inventory, int reservePercent, int cap) {
        return expedition(inventory, Map.of(), reservePercent, cap);
    }

    public static Map<String, Integer> expedition(Map<String, Integer> inventory, Map<String, Integer> committed,
                                                 int reservePercent, int cap) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int remaining = cap;
        for (String unit : List.of("infantry", "armored", "ltank", "htank", "assault", "rocket", "special", "truck")) {
            int available = Math.max(0, inventory.getOrDefault(unit, 0));
            int deployed = Math.max(0, committed.getOrDefault(unit, 0));
            int budget = (int) Math.max(0, ((long) available + deployed) * (100 - reservePercent) / 100 - deployed);
            int count = Math.min(remaining, Math.min(available, budget));
            if (count > 0) { result.put(unit, count); remaining -= count; }
        }
        return result;
    }

    public static double strength(Map<String, Integer> army) {
        double total = 0;
        for (var entry : army.entrySet()) {
            UnitDef unit = GameData.UNITS.get(BattleUnitIds.base(entry.getKey()));
            if (entry.getValue() < 0 || unit == null) return Double.POSITIVE_INFINITY;
            if (!Boolean.TRUE.equals(unit.logistic())) {
                total += entry.getValue() * Math.sqrt(Math.max(0, unit.atkGround()) * Math.max(0, unit.hp()));
            }
        }
        return total;
    }

    public static boolean favorable(Map<String, Integer> attackers, Map<String, Integer> defenders) {
        double own = strength(attackers), enemy = strength(defenders);
        return Double.isFinite(own) && own > 0 && Double.isFinite(enemy) && own >= enemy * 3;
    }
}
