package com.wargame.model.constants;

import java.util.*;

/** 科技改版的共享公式与旧标识映射；迁移、导入、科研完成使用同一份映射。 */
public final class TechnologyRules {
    public static final String VERSION = "technology-v3-20261019";
    public static final String FIVE_LEVEL_VERSION = "technology-v2-20261016";
    public static final Set<String> EXPANDED = Set.of("unit_refit", "log_warehouse", "recon_level", "recon_radar");
    private static final int[] NORMAL_SECONDS = {180, 600, 1800, 10800, 43200, 85320, 168420, 332460, 656400, 1296000};
    private static final int[] RESOURCE_SECONDS = {120, 480, 1500, 5400, 21600, 46860, 101640, 220380, 478020, 1036800};
    private static final int[] ARM_ENGINE_SECONDS = {300, 900, 2700, 10800, 43200, 90360, 188940, 395100, 826260, 1728000};
    // 舰船动力末级对齐终局项目28天，前五级固定、6～10级由同一锚点公式生成。
    private static final int[] NAV_ENGINE_SECONDS = {300, 900, 2700, 10800, 43200, 96660, 216180, 483480, 1081500, 2419200};
    private static final int[] AIR_ENGINE_SECONDS = {300, 900, 2700, 10800, 43200, 96660, 216180, 483480, 1081500, 2419200};
    public static final String LOAD_SNAPSHOT = "@log:load";
    public static final int RESEARCH_LAB_MAX_LEVEL = 10;
    public static final double RESEARCH_LAB_SPEED_PER_LEVEL = 0.01;
    public static final double RESEARCH_KNOWLEDGE_SPEED_PER_POINT = 0.001;
    public static final List<String> PRODUCTION = List.of("prod_food", "prod_steel", "prod_oil", "prod_rare");
    public static final Set<String> COMBAT = Set.of("attack_tech", "defense_tech", "cmd_hp", "fire_density", "weapon_range", "arm_engine", "air_engine", "nav_engine");
    private TechnologyRules() {}

    /** 目标等级查表，前五级固定、后五级按设计几何递进取整到分钟；不截断为24小时。 */
    public static int baseDuration(String key, int targetLevel) {
        int[] curve = switch (key) {
            case "arm_engine" -> ARM_ENGINE_SECONDS;
            case "nav_engine" -> NAV_ENGINE_SECONDS;
            case "air_engine" -> AIR_ENGINE_SECONDS;
            default -> PRODUCTION.contains(key) ? RESOURCE_SECONDS : NORMAL_SECONDS;
        };
        return curve[Math.max(1, Math.min(10, targetLevel)) - 1];
    }

    /** 五级制只扩级一次；更老存档先归一旧标识，再转换四项科技。 */
    public static Map<String, Integer> importTargets(String version, String key, int value) {
        if (VERSION.equals(version)) {
            TechDef def = TechDef.TECHS.get(key);
            return def == null ? Map.of() : Map.of(key, Math.max(0, Math.min(def.maxLevel(), value)));
        }
        Map<String, Integer> normalized = FIVE_LEVEL_VERSION.equals(version)
                ? (TechDef.TECHS.containsKey(key) ? Map.of(key, Math.max(0, value)) : Map.of())
                : legacyTargets(key, value);
        Map<String, Integer> result = new LinkedHashMap<>();
        normalized.forEach((k, v) -> result.put(k, EXPANDED.contains(k) ? 2 * Math.min(5, v) : Math.min(TechDef.TECHS.get(k).maxLevel(), v)));
        return result;
    }

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
        // 老格式中四项科技仍按当时的5级上限归一，随后由导入入口统一翻倍。
        int value = Math.max(0, level);
        if ("log_production".equals(key)) {
            Map<String, Integer> result = new LinkedHashMap<>();
            PRODUCTION.forEach(k -> result.put(k, Math.min(10, value)));
            return result;
        }
        if ("inf_load".equals(key)) return Map.of("log_load", 2 * Math.min(5, value));
        if (Set.of("unit_refit", "recon_radar").contains(key)) return Map.of(key, value == 0 ? 0 : 2 * Math.min(3, value) - 1);
        TechDef def = TechDef.TECHS.get(key);
        return def == null ? Map.of() : Map.of(key, Math.min(EXPANDED.contains(key) ? 5 : def.maxLevel(), value));
    }

    /** 明确登记百分比，情报、配方与俘获比例不能落入默认百分比。 */
    public static int effectPercent(String effect) {
        return switch (effect) {
            case "wall_stats", "atk_all", "def_all", "range_all", "hp_all", "firepower_all", "train", "spd_arm", "spd_air", "spd_nav", "res_food", "res_steel", "res_oil", "res_rare", "load", "medical" -> 5;
            case "cap" -> 5;
            case "food_save", "build" -> -5;
            default -> 0;
        };
    }
}
