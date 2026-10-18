package com.wargame.model.constants;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

    /** 陆军共用燃烧引擎；步兵系只扩展机动科技，不改变攻击、防御分类。 */
    public String speedCategory() {
        return "inf".equals(cat) ? "arm" : cat;
    }

    /** 对部队的最高攻击仅供经验与概览战力估算；攻坚针对廉价工事单独定标，不计入该估值。 */
    public double peakTroopAttack() { return Math.max(atkGround, Math.max(atkAir, atkSea)); }

    /** 海军的制造与驻泊能力必须同城具备；生产排期仅由制造建筑决定。 */
    public List<String> recruitmentBuildings() {
        return "sea".equals(branch) ? List.of(build, "port") : List.of(build);
    }

    // 四项依次为对地、对空、对海、对工事攻击；数值由 docs/balance/attack-design.json 独立推导；所有攻击至少为1，弱项仅保留低效火力，专项克制见 BattleRules。
    // 与前端共用完整历史名称，确保征召提示和新战报一致；存档与战斗规则仍使用稳定的 key。
    // 征召预备粮与单位规模、珍贵程度相关，后续驻城及行军耗粮仍单独结算。
    public static final Map<String, UnitDef> UNITS;
    static {
        Map<String, UnitDef> m = new HashMap<>();
        // 步兵每人自携10单位物资，50人等于一辆卡车；保持战斗兵种身份，负重不改变自动作战行为。
        // 步兵以低成本数量形成持续对地火力；射程和轻微攻坚提升不改变其依赖重武器突破工事的定位。
        m.put("infantry", new UnitDef("infantry", "步兵-加兰德步枪兵（M1）", "inf",
                10, 5, 5, 3, 15, 120, 2, 160, 1, 0, 1, 1, "factory",
                Map.of("food", 10, "steel", 30, "oil", 0, "rare", 0), null, "land",
                false, 10));
        m.put("truck", new UnitDef("truck", "卡车-十轮大卡（CCKW-353）", "inf",
                2, 1, 1, 1, 5.5, 150, 4, 0, 2, 2, 1, 1, "factory",
                Map.of("food", 20, "steel", 50, "oil", 15, "rare", 0), null, "land",
                true, 500, false));
        m.put("armored", new UnitDef("armored", "装甲车-猎鹿犬防空型（T17E2）", "arm",
                18, 33.5, 45, 36, 33, 360, 5, 300, 4, 3, 2, 2, "factory",
                Map.of("food", 40, "steel", 180, "oil", 60, "rare", 20), null, "land"));
        // 侦查车与防空装甲车同价同耐久，用侦查能力替代专项防空火力。
        m.put("recon_car", new UnitDef("recon_car", "侦查装甲车-灰狗（M8）", "arm",
                18, 5, 45, 36, 33, 360, 5, 300, 4, 3, 2, 2, "factory",
                Map.of("food", 40, "steel", 180, "oil", 60, "rare", 20), null, "land"));
        m.put("ltank", new UnitDef("ltank", "轻型坦克-斯图亚特（M5A1）", "arm",
                33, 10, 55, 45, 53, 270, 4, 220, 5, 4, 2, 2, "lightfactory",
                Map.of("food", 50, "steel", 240, "oil", 80, "rare", 25), "armored", "land"));
        m.put("htank", new UnitDef("htank", "重型坦克-斯大林（IS-2）", "arm",
                50, 15, 65, 50, 63.5, 385, 4, 320, 8, 7, 3, 4, "heavyfactory",
                Map.of("food", 80, "steel", 450, "oil", 120, "rare", 50), "ltank", "land"));
        m.put("assault", new UnitDef("assault", "突击炮-自行加榴炮（ISU-152）", "arm",
                34, 30, 65, 167, 28, 200, 2, 750, 4, 5, 2, 2, "factory",
                Map.of("food", 40, "steel", 200, "oil", 50, "rare", 25), "bunker", "land"));
        m.put("rocket", new UnitDef("rocket", "反坦克火箭车-喀秋莎（BM-13）", "arm",
                100, 11.5, 25, 179, 28, 150, 3, 1600, 5, 4, 3, 3, "factory",
                Map.of("food", 100, "steel", 550, "oil", 150, "rare", 70), "htank", "land"));
        // 侦察机属于空军，生产、速度和装备制造均按空军基地计算。
        m.put("scout", new UnitDef("scout", "侦察机-闪电侦察型（F-5）", "air",
                1, 4, 1, 1, 13, 70.5, 9, 200, 3, 8, 1, 1, "airport",
                Map.of("food", 30, "steel", 60, "oil", 30, "rare", 10), null, "air",
                null, null, false));
        m.put("special", new UnitDef("special", "特种兵-英国突击队（Commando）", "inf",
                30, 10, 125, 188, 5.5, 150, 6, 180, 4, 1, 2, 2, "factory",
                Map.of("food", 40, "steel", 100, "oil", 40, "rare", 20), "howitzer", "land"));
        m.put("fighter", new UnitDef("fighter", "战斗机-野马（P-51）", "air",
                12, 64, 75, 5, 30, 150, 8, 350, 5, 10, 2, 2, "airport",
                Map.of("food", 50, "steel", 220, "oil", 90, "rare", 35), "bomber", "air"));
        m.put("bomber", new UnitDef("bomber", "轰炸机-飞行堡垒（B-17G）", "air",
                56, 12, 95, 429, 22, 195, 6, 300, 7, 22, 4, 3, "airport",
                Map.of("food", 70, "steel", 350, "oil", 150, "rare", 60), "htank", "air"));
        m.put("transport", new UnitDef("transport", "运输机-空中列车（C-47）", "air",
                1, 1, 1, 1, 10, 220, 6, 0, 5, 16, 3, 2, "airport",
                Map.of("food", 50, "steel", 180, "oil", 80, "rare", 20), null, "air",
                true, 800, false));
        m.put("destroyer", new UnitDef("destroyer", "驱逐舰-弗莱彻级（Fletcher）", "nav",
                44, 59, 47, 35, 50, 555, 5, 400, 7, 15, 6, 3, "shipyard",
                Map.of("food", 70, "steel", 450, "oil", 160, "rare", 80), "sub", "sea"));
        m.put("sub", new UnitDef("sub", "潜艇-小鲨鱼级（Gato）", "nav",
                1, 1, 66, 1, 20, 395, 3, 100, 6, 9, 4, 3, "shipyard",
                Map.of("food", 60, "steel", 300, "oil", 80, "rare", 60), "battleship", "sea"));
        m.put("battleship", new UnitDef("battleship", "战列舰-衣阿华级（Iowa）", "nav",
                91, 35, 96, 108, 120, 1300, 4, 1600, 12, 35, 15, 6, "shipyard",
                Map.of("food", 120, "steel", 1200, "oil", 400, "rare", 250), "destroyer", "sea"));
        m.put("carrier", new UnitDef("carrier", "航母-埃塞克斯级（Essex）", "nav",
                82, 125, 80, 110, 70, 1100, 4, 1900, 15, 42, 22, 8, "shipyard",
                Map.of("food", 150, "steel", 1400, "oil", 500, "rare", 350), "bomber", "sea"));
        UNITS = Collections.unmodifiableMap(m);
    }
}
