package com.wargame.model.constants;

import java.util.List;
import java.util.Map;

/** 科研黄金独立按目标等级查表；不受旧资源成长、基础工期或科研加速影响。 */
public final class TechnologyGoldCosts {
    public static final String VERSION = "tech-research-gold-20261028-v6";

    private static final List<Integer> RESOURCE = List.of(4000, 20000, 80000, 240000, 720000, 2000000, 4800000, 9200000, 14400000, 20000000);
    private static final List<Integer> NORMAL = List.of(6000, 30000, 120000, 360000, 1080000, 3000000, 7200000, 13800000, 21600000, 30000000);
    private static final List<Integer> LAND = List.of(10000, 50000, 200000, 600000, 1800000, 5000000, 12000000, 23000000, 36000000, 50000000);
    private static final List<Integer> AIR = List.of(13000, 65000, 260000, 780000, 2340000, 6500000, 15600000, 29900000, 46800000, 65000000);
    private static final List<Integer> NAVAL = List.of(16000, 80000, 320000, 960000, 2880000, 8000000, 19200000, 36800000, 57600000, 80000000);

    // 显式登记全部24项；喷气和舰船虽同为28天，仍按定稿分别计价，新增科技不能静默套用普通档。
    private static final Map<String, List<Integer>> COSTS = Map.ofEntries(
            Map.entry("prod_food", RESOURCE), Map.entry("prod_steel", RESOURCE),
            Map.entry("prod_oil", RESOURCE), Map.entry("prod_rare", RESOURCE),
            Map.entry("military_theory", NORMAL), Map.entry("attack_tech", NORMAL),
            Map.entry("defense_tech", NORMAL), Map.entry("weapon_range", NORMAL),
            Map.entry("cmd_hp", NORMAL), Map.entry("fire_density", NORMAL),
            Map.entry("wall_reinforcement", NORMAL), Map.entry("log_load", NORMAL),
            Map.entry("log_warehouse", NORMAL), Map.entry("log_food", NORMAL),
            Map.entry("log_train", NORMAL), Map.entry("log_build", NORMAL),
            Map.entry("log_medical", NORMAL), Map.entry("recon_level", NORMAL),
            Map.entry("recon_radar", NORMAL), Map.entry("prisoner_education", NORMAL),
            Map.entry("unit_refit", NORMAL), Map.entry("arm_engine", LAND),
            Map.entry("air_engine", AIR), Map.entry("nav_engine", NAVAL));

    private TechnologyGoldCosts() {}

    /** 按升级后的目标等级取费用；满级卡片预览与工期一样夹取1～10级，开工仍单独校验上限。 */
    public static int forTarget(String techType, int targetLevel) {
        List<Integer> curve = COSTS.get(techType);
        if (curve == null) throw new IllegalArgumentException("科技未配置黄金费用: " + techType);
        return curve.get(Math.max(1, Math.min(10, targetLevel)) - 1);
    }
}
