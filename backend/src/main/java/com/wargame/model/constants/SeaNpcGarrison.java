package com.wargame.model.constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 海洋寇按连续海战强度预算和阶段编队生成守军；预算不改变战斗伤害公式。 */
public final class SeaNpcGarrison {
    private SeaNpcGarrison() {}

    /** 1 级为原潜艇满编的一半；各级统一增长 20%，新增舰种占用原预算。 */
    public static double budget(int level) {
        if (level < 1 || level > WorldConfig.MAX_NPC_LEVEL)
            throw new IllegalArgumentException("海洋寇等级必须为 1～30");
        return 200 * Math.pow(1.2, level - 1);
    }

    /** 按生成坐标稳定选择同级编队，侦察和开战只读取已保存的军队。 */
    public static Map<String, Integer> army(int level, long seed) {
        var choices = formations(level);
        return choices.get(Math.floorMod(seed, choices.size()));
    }

    /** 返回本等级全部合法满编；高等级保持专业缺口，不把所有海空单位塞进同一编队。 */
    public static List<Map<String, Integer>> formations(int level) {
        double total = budget(level);
        List<Map<String, Double>> profiles;
        if (level <= 3) {
            profiles = List.of(Map.of("sub", 100.0));
        } else if (level <= 10) {
            double destroyer = (level - 3) * 5.0;
            profiles = List.of(Map.of("sub", 100 - destroyer, "destroyer", destroyer));
        } else if (level <= 20) {
            double battleship = Math.min(45, (level - 10) * 4.5);
            double destroyer = Math.min(45, 25 + (level - 11) * 1.5);
            profiles = new ArrayList<>();
            profiles.add(Map.of("sub", 100 - destroyer - battleship, "destroyer", destroyer,
                    "battleship", battleship));
            profiles.add(Map.of("sub", 45.0 - battleship * .35, "destroyer", 20.0,
                    "battleship", 35.0 + battleship * .35));
            profiles.add(Map.of("sub", 25.0, "destroyer", 55.0 - battleship * .25,
                    "battleship", 20.0 + battleship * .25));
        } else if (level <= 24) {
            double carrier = (level - 20) * 5.0;
            double air = carrier * 1.5;
            profiles = new ArrayList<>();
            profiles.add(Map.of("sub", 55.0 - carrier * .5, "destroyer", 25.0,
                    "battleship", 20.0 - carrier * .5, "carrier", carrier));
            profiles.add(Map.of("destroyer", 30.0, "battleship", 25.0 - carrier * .5,
                    "carrier", carrier, "fighter", 45.0 - carrier * .5));
            profiles.add(Map.of("sub", 80.0 - carrier * 2, "destroyer", 20.0,
                    "carrier", carrier, "fighter", carrier * .8, "bomber", carrier * .2));
            profiles.add(Map.of("battleship", 45.0, "carrier", carrier,
                    "bomber", carrier * .5, "destroyer", 55.0 - carrier * 1.5));
        } else {
            double step = level - 25;
            // 高阶编队保留明显弱点：潜艇狼群、航母空袭、水面决战和护航防空互相克制。
            profiles = List.of(
                    Map.of("sub", 70 - step * 3.0, "destroyer", 30 + step * 3.0),
                    Map.of("carrier", 25 + step * 3.0, "fighter", 50 - step * 2.0,
                            "bomber", 25 - step),
                    Map.of("destroyer", 40.0, "battleship", 45 + step * 2.0,
                            "sub", 15 - step * 2.0),
                    Map.of("destroyer", 40 - step * 2.0, "carrier", 25.0,
                            "fighter", 35 + step * 2.0),
                    Map.of("sub", 25.0, "battleship", 35 - step * 2.0,
                            "carrier", 20.0, "bomber", 20 + step * 2.0),
                    Map.of("destroyer", 45 - step * 2.0, "battleship", 30.0,
                            "carrier", 25 + step * 2.0));
        }
        return profiles.stream().map(profile -> allocate(profile, total)).toList();
    }

    /** 将舰艇和舰载机按海战属性换算为潜艇等效强度，避免解锁新舰种造成额外跳升。 */
    public static double strength(Map<String, Integer> army) {
        return army.entrySet().stream().mapToDouble(e -> e.getValue() * weight(e.getKey())).sum();
    }

    /** 按强度份额配兵，逐兵种向下取整并保证有份额的兵种至少保留 1 个。 */
    private static Map<String, Integer> allocate(Map<String, Double> profile, double total) {
        Map<String, Integer> army = new LinkedHashMap<>();
        profile.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (e.getValue() > 0)
                army.put(e.getKey(), Math.max(1, (int) Math.floor(total * e.getValue() / 100 / weight(e.getKey()))));
        });
        return Collections.unmodifiableMap(army);
    }

    /** 使用海战区 70% 对海、30% 对空火力与单位生存属性计算单位权重。 */
    private static double weight(String unit) {
        return unitValue(UnitDef.UNITS.get(unit)) / unitValue(UnitDef.UNITS.get("sub"));
    }

    private static double unitValue(UnitDef unit) {
        double attack = .7 * unit.atkSea() + .3 * unit.atkAir();
        double mobility = 1 + Math.min(.20, unit.range() / 10000.0) + Math.min(.10, unit.spd() / 100.0);
        return Math.sqrt(Math.max(1, attack) * unit.hp()) * (1 + unit.def() / 100) * mobility;
    }
}
