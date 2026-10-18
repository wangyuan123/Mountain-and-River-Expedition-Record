package com.wargame.model.constants;

import java.util.*;

/** 科技改版的共享公式与旧标识映射；迁移、导入、科研完成使用同一份映射。 */
public final class TechnologyRules {
    public static final String VERSION = "technology-v2-20261016";
    public static final String LOAD_SNAPSHOT = "@log:load";
    public static final List<String> PRODUCTION = List.of("prod_food", "prod_steel", "prod_oil", "prod_rare");
    public static final Set<String> COMBAT = Set.of("attack_tech", "defense_tech", "cmd_hp", "fire_density", "weapon_range", "arm_engine", "air_engine", "nav_engine");
    private TechnologyRules() {}

    public static int level(Map<String, Integer> tech, String key, int max) {
        return Math.max(0, Math.min(max, tech == null ? 0 : Objects.requireNonNullElse(tech.get(key), 0)));
    }

    /** 六项军事/训练和三军引擎均每级5%，先夹取合法等级再形成线性倍率。 */
    public static double multiplier(Map<String, Integer> tech, String key) {
        return 1 + .05 * level(tech, key, 10);
    }

    /** 行动快照冻结负重科技；缺键的旧行军保持零级，不回读当前账号补算。 */
    public static Map<String, Integer> actionSnapshot(Map<String, Integer> tech) {
        Map<String, Integer> result = UnitEquipmentEffects.only(tech);
        result.put(LOAD_SNAPSHOT, level(tech, "log_load", 10));
        return result;
    }

    public static double loadMultiplier(Map<String, Integer> snapshot) {
        return 1 + .05 * level(snapshot, LOAD_SNAPSHOT, 10);
    }

    /** 旧资源科研保持单条付费队列，完成时一次写入四项；不拆成四份重复退款。 */
    public static Map<String, Integer> legacyTargets(String key, int level) {
        int value = Math.max(0, level);
        if ("log_production".equals(key)) {
            Map<String, Integer> result = new LinkedHashMap<>();
            PRODUCTION.forEach(k -> result.put(k, Math.min(10, value)));
            return result;
        }
        if ("inf_load".equals(key)) return Map.of("log_load", 2 * Math.min(5, value));
        if (Set.of("unit_refit", "recon_radar").contains(key)) return Map.of(key, value == 0 ? 0 : 2 * Math.min(3, value) - 1);
        TechDef def = TechDef.TECHS.get(key);
        return def == null ? Map.of() : Map.of(key, Math.min(def.maxLevel(), value));
    }

    /** 明确登记百分比，情报、配方与俘获比例不能落入默认百分比。 */
    public static int effectPercent(String effect) {
        return switch (effect) {
            case "wall_stats", "atk_all", "def_all", "range_all", "hp_all", "firepower_all", "train", "spd_arm", "spd_air", "spd_nav", "res_food", "res_steel", "res_oil", "res_rare", "load", "medical" -> 5;
            case "cap" -> 10;
            case "food_save", "build" -> -5;
            default -> 0;
        };
    }
}
