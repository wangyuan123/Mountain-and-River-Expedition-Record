package com.wargame.model.constants;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 单位定义 - 对应 data.js 中 G.DATA.units
 */
public record UnitDef(
        String key,
        String name,
        String cat,
        int atk,
        int def,
        int hp,
        int spd,
        int range,
        int food,
        int pop,
        String build,
        Map<String, Integer> cost,
        String strongVs,
        String branch,
        Boolean logistic,
        Integer load,
        Boolean autoAdvance
) {
    /** 便捷构造器，用于没有可选字段的单位 */
    public UnitDef(String key, String name, String cat, int atk, int def, int hp, int spd, int range,
                   int food, int pop, String build, Map<String, Integer> cost, String strongVs, String branch) {
        this(key, name, cat, atk, def, hp, spd, range, food, pop, build, cost, strongVs, branch, null, null, null);
    }

    /** 便捷构造器，带有后勤和载重 */
    public UnitDef(String key, String name, String cat, int atk, int def, int hp, int spd, int range,
                   int food, int pop, String build, Map<String, Integer> cost, String strongVs, String branch,
                   boolean logistic, int load) {
        this(key, name, cat, atk, def, hp, spd, range, food, pop, build, cost, strongVs, branch, logistic, load, null);
    }

    public static final Map<String, UnitDef> UNITS;
    static {
        Map<String, UnitDef> m = new HashMap<>();
        m.put("infantry", new UnitDef("infantry", "步兵", "inf",
                6, 4, 30, 3, 100, 1, 1, "factory",
                Map.of("steel", 20, "oil", 0, "rare", 0), null, "land"));
        m.put("motor", new UnitDef("motor", "摩托兵", "inf",
                10, 4, 30, 7, 100, 2, 1, "factory",
                Map.of("steel", 40, "oil", 10, "rare", 0), "infantry", "land"));
        m.put("truck", new UnitDef("truck", "卡车", "inf",
                2, 6, 50, 8, 0, 2, 1, "factory",
                Map.of("steel", 60, "oil", 20, "rare", 0), null, "land",
                true, 50, false));
        m.put("armored", new UnitDef("armored", "装甲车", "arm",
                18, 12, 80, 7, 120, 4, 2, "factory",
                Map.of("steel", 120, "oil", 40, "rare", 10), "fighter", "land"));
        m.put("ltank", new UnitDef("ltank", "轻型坦克", "arm",
                28, 22, 120, 6, 130, 5, 2, "lightfactory",
                Map.of("steel", 200, "oil", 60, "rare", 20), "armored", "land"));
        m.put("htank", new UnitDef("htank", "重型坦克", "arm",
                50, 40, 220, 4, 140, 8, 4, "heavyfactory",
                Map.of("steel", 400, "oil", 120, "rare", 50), "ltank", "land"));
        m.put("assault", new UnitDef("assault", "突击炮", "arm",
                60, 18, 120, 4, 300, 7, 3, "heavyfactory",
                Map.of("steel", 360, "oil", 100, "rare", 60), "htank", "land"));
        m.put("rocket", new UnitDef("rocket", "火箭", "arm",
                90, 14, 100, 4, 350, 9, 4, "heavyfactory",
                Map.of("steel", 500, "oil", 160, "rare", 100), "htank", "land"));
        m.put("scout", new UnitDef("scout", "侦察机", "air",
                4, 6, 30, 14, 200, 3, 1, "factory",
                Map.of("steel", 80, "oil", 40, "rare", 10), null, "air",
                null, null, false));
        m.put("special", new UnitDef("special", "特种兵", "air",
                24, 14, 60, 13, 180, 4, 2, "factory",
                Map.of("steel", 160, "oil", 60, "rare", 30), null, "air"));
        m.put("fighter", new UnitDef("fighter", "战斗机", "air",
                35, 22, 90, 12, 260, 5, 2, "factory",
                Map.of("steel", 200, "oil", 80, "rare", 30), "bomber", "air"));
        m.put("bomber", new UnitDef("bomber", "轰炸机", "air",
                70, 16, 110, 9, 280, 7, 3, "factory",
                Map.of("steel", 320, "oil", 140, "rare", 60), "htank", "air"));
        m.put("transport", new UnitDef("transport", "运输机", "air",
                2, 12, 120, 8, 0, 5, 2, "factory",
                Map.of("steel", 240, "oil", 100, "rare", 30), null, "air",
                true, 80, null));
        m.put("destroyer", new UnitDef("destroyer", "驱逐舰", "nav",
                40, 28, 160, 6, 250, 7, 3, "port",
                Map.of("steel", 300, "oil", 120, "rare", 60), "sub", "sea"));
        m.put("sub", new UnitDef("sub", "潜艇", "nav",
                65, 18, 110, 5, 230, 6, 3, "port",
                Map.of("steel", 360, "oil", 100, "rare", 80), "battleship", "sea"));
        m.put("battleship", new UnitDef("battleship", "战列舰", "nav",
                100, 60, 360, 4, 320, 12, 6, "port",
                Map.of("steel", 700, "oil", 240, "rare", 160), "destroyer", "sea"));
        m.put("carrier", new UnitDef("carrier", "航母", "nav",
                130, 40, 280, 4, 400, 15, 8, "port",
                Map.of("steel", 900, "oil", 300, "rare", 240), null, "sea"));
        UNITS = Collections.unmodifiableMap(m);
    }
}
