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
        double atkGround, double atkAir, double atkSea, double atkFort,
        double def,
        double hp,
        int spd,
        int range,
        int food,
        int marchOil,
        int marchFood,
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
    public UnitDef(String key, String name, String cat, double atkGround, double atkAir, double atkSea, double atkFort, double def, double hp, int spd, int range,
                   int food, int marchOil, int marchFood, int pop, String build, Map<String, Integer> cost, String strongVs, String branch) {
        this(key, name, cat, atkGround, atkAir, atkSea, atkFort, def, hp, spd, range, food, marchOil, marchFood, pop, build, cost, strongVs, branch, null, null, null);
    }

    /** 便捷构造器，带有后勤和载重 */
    public UnitDef(String key, String name, String cat, double atkGround, double atkAir, double atkSea, double atkFort, double def, double hp, int spd, int range,
                   int food, int marchOil, int marchFood, int pop, String build, Map<String, Integer> cost, String strongVs, String branch,
                   boolean logistic, int load) {
        this(key, name, cat, atkGround, atkAir, atkSea, atkFort, def, hp, spd, range, food, marchOil, marchFood, pop, build, cost, strongVs, branch, logistic, load, null);
    }

    /** 对部队的最高攻击仅供经验与概览战力估算；攻坚针对廉价工事单独定标，不计入该估值。 */
    public double peakTroopAttack() { return Math.max(atkGround, Math.max(atkAir, atkSea)); }

    // 四项依次为对地、对空、对海、对工事攻击；数值由 docs/balance/attack-design.json 独立推导；所有攻击至少为1，弱项仅保留低效火力，专项克制见 BattleRules。
    // 与前端共用完整历史名称，确保征召提示和新战报一致；存档与战斗规则仍使用稳定的 key。
    public static final Map<String, UnitDef> UNITS;
    static {
        Map<String, UnitDef> m = new HashMap<>();
        m.put("infantry", new UnitDef("infantry", "步兵-加兰德步枪兵（M1）", "inf",
                6, 5, 5, 2, 15, 120, 3, 100, 1, 0, 1, 1, "factory",
                Map.of("steel", 30, "oil", 0, "rare", 0), null, "land"));
        m.put("motor", new UnitDef("motor", "摩托兵-哈雷（WLA）", "inf",
                12, 5, 5, 5, 13, 100, 7, 140, 2, 1, 1, 1, "factory",
                Map.of("steel", 35, "oil", 10, "rare", 0), "infantry", "land"));
        m.put("truck", new UnitDef("truck", "卡车-十轮大卡（CCKW-353）", "inf",
                2, 1, 1, 1, 5.5, 150, 6, 0, 2, 2, 1, 1, "factory",
                Map.of("steel", 50, "oil", 15, "rare", 0), null, "land",
                true, 50, false));
        m.put("armored", new UnitDef("armored", "装甲车-猎鹿犬防空型（T17E2）", "arm",
                18, 33.5, 45, 36, 33, 360, 7, 300, 4, 3, 2, 2, "factory",
                Map.of("steel", 180, "oil", 60, "rare", 20), "motor", "land"));
        m.put("ltank", new UnitDef("ltank", "轻型坦克-斯图亚特（M5A1）", "arm",
                33, 10, 55, 45, 53, 270, 6, 220, 5, 4, 2, 2, "lightfactory",
                Map.of("steel", 240, "oil", 80, "rare", 25), "armored", "land"));
        m.put("htank", new UnitDef("htank", "重型坦克-斯大林（IS-2）", "arm",
                50, 15, 65, 50, 63.5, 385, 6, 320, 8, 7, 3, 4, "heavyfactory",
                Map.of("steel", 450, "oil", 120, "rare", 50), "ltank", "land"));
        m.put("assault", new UnitDef("assault", "突击炮-自行加榴炮（ISU-152）", "arm",
                34, 30, 65, 167, 28, 200, 4, 750, 4, 5, 2, 2, "factory",
                Map.of("steel", 200, "oil", 50, "rare", 25), "bunker", "land"));
        m.put("rocket", new UnitDef("rocket", "火箭-喀秋莎（BM-13）", "arm",
                100, 5, 25, 179, 28, 150, 5, 2000, 5, 4, 3, 3, "factory",
                Map.of("steel", 220, "oil", 70, "rare", 45), "htank", "land"));
        m.put("scout", new UnitDef("scout", "侦察机-闪电侦察型（F-5）", "air",
                1, 4, 1, 1, 13, 70.5, 11, 200, 3, 8, 1, 1, "factory",
                Map.of("steel", 60, "oil", 30, "rare", 10), null, "air",
                null, null, false));
        m.put("special", new UnitDef("special", "特种兵-英国突击队（Commando）", "inf",
                30, 10, 125, 188, 5.5, 150, 8, 180, 4, 1, 2, 2, "factory",
                Map.of("steel", 100, "oil", 40, "rare", 20), "howitzer", "land"));
        m.put("fighter", new UnitDef("fighter", "战斗机-野马（P-51）", "air",
                12, 64, 75, 5, 30, 150, 10, 350, 5, 10, 2, 2, "factory",
                Map.of("steel", 220, "oil", 90, "rare", 35), "bomber", "air"));
        m.put("bomber", new UnitDef("bomber", "轰炸机-飞行堡垒（B-17G）", "air",
                56, 12, 95, 429, 22, 195, 8, 300, 7, 22, 4, 3, "factory",
                Map.of("steel", 350, "oil", 150, "rare", 60), "htank", "air"));
        m.put("transport", new UnitDef("transport", "运输机-空中列车（C-47）", "air",
                1, 1, 1, 1, 10, 220, 8, 0, 5, 16, 3, 2, "factory",
                Map.of("steel", 180, "oil", 80, "rare", 20), null, "air",
                true, 80, false));
        m.put("destroyer", new UnitDef("destroyer", "驱逐舰-弗莱彻级（Fletcher）", "nav",
                44, 59, 47, 35, 50, 555, 7, 400, 7, 15, 6, 3, "port",
                Map.of("steel", 450, "oil", 160, "rare", 80), "sub", "sea"));
        m.put("sub", new UnitDef("sub", "潜艇-小鲨鱼级（Gato）", "nav",
                1, 1, 66, 1, 20, 395, 5, 100, 6, 9, 4, 3, "port",
                Map.of("steel", 300, "oil", 80, "rare", 60), "battleship", "sea"));
        m.put("battleship", new UnitDef("battleship", "战列舰-衣阿华级（Iowa）", "nav",
                91, 35, 96, 108, 120, 1300, 6, 1600, 12, 35, 15, 6, "port",
                Map.of("steel", 1200, "oil", 400, "rare", 250), "destroyer", "sea"));
        m.put("carrier", new UnitDef("carrier", "航母-埃塞克斯级（Essex）", "nav",
                82, 125, 80, 110, 70, 1100, 6, 1900, 15, 42, 22, 8, "port",
                Map.of("steel", 1400, "oil", 500, "rare", 350), "bomber", "sea"));
        UNITS = Collections.unmodifiableMap(m);
    }
}
