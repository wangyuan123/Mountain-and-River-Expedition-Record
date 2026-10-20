package com.wargame.model.constants;

import java.util.*;

/** 账号兵种改装目录；解锁等级和配方类型逐件定义，装备数量与兵数无关。 */
public record UnitEquipmentDef(String key, String unit, String slot, String name, int level, int facilityLevel,
                               String recipe, Map<String, Integer> effects,
                               Map<String, Integer> cost, int seconds) {
    public static final List<String> SLOTS = List.of("protection", "combat", "support");
    public static final Map<String, UnitEquipmentDef> ITEMS;
    static {
        Map<String, UnitEquipmentDef> m = new LinkedHashMap<>();
        add(m, "infantry", "protection", "helmet", "加强钢盔包", 1, 2, "ordinary", Map.of("defense", 8));
        add(m, "infantry", "protection", "vest", "防破片护具套", 7, 8, "special", Map.of("defense", 8, "hp", 12, "speed", -6));
        add(m, "infantry", "combat", "lmg", "班用轻机枪组", 3, 4, "combat", Map.of("ground", 10, "food", 5));
        add(m, "infantry", "support", "radio", "班排无线电组", 5, 6, "precision", Map.of("ground", 5, "battleSpeed", 5));
        add(m, "truck", "protection", "armor", "驾驶室防护板", 1, 2, "ordinary", Map.of("defense", 15, "speed", -5));
        add(m, "truck", "combat", "cargo", "加强货架", 3, 4, "combat", Map.of("load", 20, "oil", 10));
        add(m, "truck", "support", "economy", "节油维护包", 6, 6, "precision", Map.of("oil", -15));
        add(m, "truck", "support", "dispatch", "车队调度包", 5, 6, "precision", Map.of("marchSpeed", 8));
        add(m, "armored", "protection", "armor", "炮位防护板", 1, 2, "ordinary", Map.of("defense", 12, "speed", -5));
        add(m, "armored", "combat", "sight", "防空瞄具改良", 3, 4, "combat", Map.of("air", 10));
        add(m, "armored", "support", "radio", "射击通讯组", 5, 6, "precision", Map.of("air", 5, "battleSpeed", 5));
        add(m, "armored", "support", "economy", "节油维护包", 6, 6, "precision", Map.of("oil", -15));
        add(m, "recon_car", "protection", "armor", "车体附加防护", 1, 2, "ordinary", Map.of("defense", 10, "speed", -4));
        add(m, "recon_car", "combat", "mission", "侦察任务油料包", 6, 6, "precision", Map.of("oil", -15));
        add(m, "recon_car", "support", "radio", "侦察车队电台", 5, 6, "precision", Map.of("battleSpeed", 8));
        add(m, "recon_car", "support", "engine", "机动调校", 6, 6, "precision", Map.of("speed", 10, "oil", 10));
        add(m, "ltank", "protection", "armor", "间隔装甲包", 7, 8, "special", Map.of("defense", 12, "speed", -6));
        add(m, "ltank", "combat", "gun", "主炮弹药与瞄具改良", 3, 4, "combat", Map.of("ground", 10));
        add(m, "ltank", "support", "radio", "车载电台", 5, 6, "precision", Map.of("ground", 5, "battleSpeed", 5));
        add(m, "ltank", "support", "liner", "抗爆防破片衬层", 9, 10, "special", Map.of("reduce_rocket", 15, "speed", -3));
        add(m, "htank", "protection", "armor", "附加装甲包", 7, 8, "special", Map.of("defense", 15, "speed", -8));
        add(m, "htank", "combat", "gun", "主炮弹药与瞄具改良", 3, 4, "combat", Map.of("ground", 10));
        add(m, "htank", "support", "radio", "车载电台", 5, 6, "precision", Map.of("ground", 5, "battleSpeed", 5));
        add(m, "htank", "support", "liner", "抗爆防破片衬层", 9, 10, "special", Map.of("reduce_rocket", 15, "speed", -3));
        add(m, "assault", "protection", "armor", "战斗室防护改良", 1, 2, "ordinary", Map.of("hp", 12, "speed", -5));
        add(m, "assault", "combat", "charge", "攻坚装药保障", 3, 4, "combat", Map.of("fort", 10, "oil", 5));
        add(m, "assault", "support", "radio", "炮兵通讯组", 5, 6, "precision", Map.of("fort", 5, "battleSpeed", 5));
        add(m, "assault", "support", "liner", "抗爆防破片衬层", 9, 10, "special", Map.of("reduce_rocket", 15, "speed", -3));
        add(m, "rocket", "protection", "armor", "弹药区防破片隔板", 1, 2, "ordinary", Map.of("hp", 12, "speed", -5));
        add(m, "rocket", "combat", "calibration", "发射架校准", 3, 4, "combat", Map.of("ground", 8, "fort", 8));
        add(m, "rocket", "support", "radio", "射击诸元通讯组", 5, 6, "precision", Map.of("ground", 5, "fort", 5));
        add(m, "rocket", "support", "engine", "机动维护包", 6, 6, "precision", Map.of("speed", 8, "oil", 8));
        add(m, "special", "protection", "armor", "轻型防护胸甲", 1, 2, "ordinary", Map.of("hp", 10, "speed", -5));
        add(m, "special", "combat", "tools", "爆破工具组", 3, 4, "combat", Map.of("fort", 10));
        add(m, "special", "support", "radio", "便携无线电", 5, 6, "precision", Map.of("ground", 5, "fort", 5));
        add(m, "special", "support", "mobility", "轻装机动包", 6, 6, "precision", Map.of("battleSpeed", 8));
        add(m, "scout", "protection", "armor", "乘员及油箱防护改良", 2, 2, "ordinary", Map.of("hp", 12, "speed", -5));
        add(m, "fighter", "protection", "armor", "装甲背板与油箱防护改良", 2, 2, "ordinary", Map.of("hp", 12, "speed", -5));
        add(m, "bomber", "protection", "armor", "防护与灭火改良", 2, 2, "ordinary", Map.of("hp", 15, "speed", -6));
        add(m, "transport", "protection", "armor", "油箱与机组防护改良", 2, 2, "ordinary", Map.of("hp", 12, "speed", -5));
        add(m, "scout", "combat", "mission", "侦察任务保障包", 6, 6, "precision", Map.of("oil", -15));
        add(m, "scout", "support", "radio", "长程通讯改良", 5, 6, "precision", Map.of("battleSpeed", 8));
        add(m, "scout", "support", "engine", "动力调校", 6, 6, "precision", Map.of("speed", 10, "oil", 10));
        add(m, "fighter", "combat", "sight", "射击瞄具改良", 4, 4, "combat", Map.of("air", 10));
        add(m, "fighter", "support", "radio", "航空无线电", 5, 6, "precision", Map.of("air", 5, "battleSpeed", 5));
        add(m, "fighter", "support", "engine", "动力调校", 6, 6, "precision", Map.of("speed", 10, "oil", 10));
        add(m, "bomber", "combat", "sight", "轰炸瞄准保障", 4, 4, "combat", Map.of("fort", 10));
        add(m, "bomber", "support", "radio", "轰炸编队通讯", 5, 6, "precision", Map.of("ground", 5, "fort", 5));
        add(m, "bomber", "support", "economy", "节油维护", 6, 6, "precision", Map.of("oil", -15));
        add(m, "transport", "combat", "cargo", "货舱与系固改良", 4, 4, "combat", Map.of("load", 20, "oil", 10));
        add(m, "transport", "support", "economy", "航路保障包", 6, 6, "precision", Map.of("oil", -15));
        add(m, "transport", "support", "engine", "动力调校", 6, 6, "precision", Map.of("speed", 8, "oil", 10));
        add(m, "destroyer", "protection", "armor", "损管设施改良", 2, 2, "ordinary", Map.of("hp", 12));
        add(m, "destroyer", "support", "economy", "节油航行保障", 6, 6, "precision", Map.of("oil", -15));
        add(m, "sub", "protection", "armor", "舱段防护改良", 2, 2, "ordinary", Map.of("hp", 10, "speed", -5));
        add(m, "sub", "support", "economy", "节油航行保障", 6, 6, "precision", Map.of("oil", -15));
        add(m, "battleship", "protection", "armor", "损管与舱段防护", 8, 8, "special", Map.of("hp", 15, "speed", -5));
        add(m, "battleship", "support", "economy", "节油航行保障", 6, 6, "precision", Map.of("oil", -15));
        add(m, "carrier", "protection", "armor", "舰内防火与损管", 8, 8, "special", Map.of("hp", 15));
        add(m, "carrier", "support", "economy", "节油航行保障", 6, 6, "precision", Map.of("oil", -15));
        add(m, "destroyer", "combat", "firecontrol", "舰炮与鱼雷火控改良", 10, 10, "precision", Map.of("sea", 10));
        add(m, "destroyer", "support", "sonar", "反潜声呐改良", 9, 10, "special", Map.of("against_sub", 5));
        add(m, "sub", "combat", "torpedo", "鱼雷射击诸元改良", 10, 10, "precision", Map.of("sea", 10));
        add(m, "sub", "support", "quiet", "低噪维护包", 9, 10, "special", Map.of("reduce_destroyer", 12, "speed", -8));
        add(m, "battleship", "combat", "firecontrol", "主炮火控改良", 10, 10, "precision", Map.of("sea", 10, "fort", 10));
        add(m, "battleship", "support", "radio", "舰队射击通讯", 5, 6, "precision", Map.of("sea", 5, "fort", 5));
        add(m, "carrier", "combat", "airgroup", "舰载机出动保障", 10, 10, "precision", Map.of("ground", 10, "fort", 10));
        add(m, "carrier", "support", "radio", "舰载机指挥通讯", 5, 6, "precision", Map.of("air", 5, "battleSpeed", 5));
        ITEMS = Collections.unmodifiableMap(m);
    }

    /** 科技解锁与生产设施要求独立；扩级不改变既有配方费用、制造工期和效果。 */
    private static void add(Map<String, UnitEquipmentDef> out, String unit, String slot, String suffix,
                            String name, int level, int facilityLevel, String recipe, Map<String, Integer> effects) {
        UnitDef u = UnitDef.UNITS.get(unit);
        if (u == null) return;
        int steel = u.cost().getOrDefault("steel", 0), oil = u.cost().getOrDefault("oil", 0), rare = u.cost().getOrDefault("rare", 0);
        Map<String, Integer> cost;
        int seconds;
        switch (recipe) {
            case "ordinary" -> { cost = Map.of("steel", Math.max(300, 4 * steel), "oil", (oil + 1) / 2, "rare", (rare + 3) / 4); seconds = 300; }
            case "combat" -> { cost = Map.of("steel", Math.max(500, 5 * steel), "oil", Math.max(50, oil), "rare", (rare + 1) / 2); seconds = 600; }
            case "precision" -> { cost = Map.of("steel", Math.max(600, 3 * steel), "oil", Math.max(100, (oil + 1) / 2), "rare", Math.max(30, rare)); seconds = 600; }
            default -> { cost = Map.of("steel", Math.max(600, 6 * steel), "oil", oil, "rare", Math.max(20, (rare + 1) / 2)); seconds = 900; }
        }
        String key = "ue_" + unit + "_" + suffix;
        out.put(key, new UnitEquipmentDef(key, unit, slot, name, level, facilityLevel, recipe, effects, cost, seconds));
    }
}
