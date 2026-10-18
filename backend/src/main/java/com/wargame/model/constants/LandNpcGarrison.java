package com.wargame.model.constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 陆地寇按连续强度预算和阶段编队生成守军；预算仅用于配兵，不参与战斗伤害计算。 */
public final class LandNpcGarrison {
    private LandNpcGarrison() {}

    /** 以一级 300 步兵为起点，各级统一增长 20%，解锁新兵种时不另加预算。 */
    public static double budget(int level) {
        if (level < 1 || level > WorldConfig.MAX_NPC_LEVEL)
            throw new IllegalArgumentException("陆地寇等级必须为 1～30");
        return 300 * Math.pow(1.2, level - 1);
    }

    /** 按生成位置的种子选取同级编队；调用方保存兵力，侦察和开战不重新选队。 */
    public static Map<String, Integer> army(int level, long seed) {
        var choices = formations(level);
        return choices.get(Math.floorMod(seed, choices.size()));
    }

    /** 返回本等级全部合法满编，21～24 级延续此前兵种，25 级起每队仅保留 2～4 种。 */
    public static List<Map<String, Integer>> formations(int level) {
        double total = budget(level);
        List<Map<String, Double>> profiles;
        if (level <= 5) {
            profiles = List.of(Map.of("infantry", 100.0));
        } else if (level <= 10) {
            double armor = (level - 5) * 5.0;
            profiles = List.of(Map.of("infantry", 100 - armor, "armored", armor));
        } else if (level <= 24) {
            double light = Math.min(50, (level - 10) * 5.0);
            double mixed = Math.min(50, (level - 10) * 6.0);
            profiles = new ArrayList<>();
            profiles.add(Map.of("infantry", 75 - light * .8, "armored", 25 - light * .2, "ltank", light));
            // 战斗机先在 11～15 级逐步加入；轰炸机从 16 级少量加入，24 级形成纯航空编队。
            double bombing = Math.max(0, (level - 15) / 9.0);
            double fighter = level <= 15 ? (level - 10) * 8.0 : 40 + 25 * bombing;
            double bomber = 35 * bombing;
            double ground = 100 - fighter - bomber;
            profiles.add(Map.of("infantry", ground * .75, "armored", ground * .25,
                    "fighter", fighter, "bomber", bomber));
            profiles.add(Map.of("infantry", (100 - mixed) * .75, "armored", (100 - mixed) * .25,
                    "ltank", mixed / 2, "fighter", mixed / 2));
            if (level >= 16) {
                double support = (level - 15) * 3.0;
                profiles.add(Map.of("infantry", 45.0, "armored", 15.0, "ltank", 40 - support,
                        "bomber", support));
            }
        } else {
            double step = level - 25;
            // 高阶使用有明确缺口的编队白名单，火箭/特种兵及重坦/轰炸机从结构上互斥。
            profiles = List.of(
                    Map.of("fighter", 65 - step * 3, "bomber", 35 + step * 3),
                    Map.of("ltank", 85 - step * 3, "rocket", 15 + step * 3),
                    Map.of("infantry", 35 - step * 2, "armored", 20.0,
                            "htank", 10 + step * 4, "fighter", 35 - step * 2),
                    Map.of("infantry", 50 - step * 2, "special", 10 + step * 4, "fighter", 40 - step * 2),
                    Map.of("armored", 25.0, "ltank", 45 - step * 2, "bomber", 30 + step * 2),
                    Map.of("armored", 55 - step * 3, "htank", 30 + step * 2, "rocket", 15 + step));
        }
        return profiles.stream().map(profile -> allocate(profile, total)).toList();
    }

    /** 把不同兵种换算为步兵等效强度，避免同数量的重装备在解锁时额外抬高难度。 */
    public static double strength(Map<String, Integer> army) {
        return army.entrySet().stream().mapToDouble(e -> e.getValue() * weight(e.getKey())).sum();
    }

    /** 按强度份额而非数量份额配兵，逐兵种向下取整且移除未解锁的零份额。 */
    private static Map<String, Integer> allocate(Map<String, Double> profile, double total) {
        Map<String, Integer> army = new LinkedHashMap<>();
        profile.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (e.getValue() > 0)
                army.put(e.getKey(), Math.max(1, (int) Math.floor(total * e.getValue() / 100 / weight(e.getKey()))));
        });
        return Collections.unmodifiableMap(army);
    }

    /** 采用陆空概览估值：70% 对地与 30% 对空火力、生命、防御、射程及速度，步兵归一为 1。 */
    private static double weight(String unit) {
        return unitValue(UnitDef.UNITS.get(unit)) / unitValue(UnitDef.UNITS.get("infantry"));
    }

    private static double unitValue(UnitDef unit) {
        double attack = .7 * unit.atkGround() + .3 * unit.atkAir();
        double mobility = 1 + Math.min(.20, unit.range() / 10000.0) + Math.min(.10, unit.spd() / 100.0);
        return Math.sqrt(Math.max(1, attack) * unit.hp()) * (1 + unit.def() / 100) * mobility;
    }
}
