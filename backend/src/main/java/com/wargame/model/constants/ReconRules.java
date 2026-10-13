package com.wargame.model.constants;

import java.util.Map;
import java.util.Set;

/** 侦查任务只允许单一方式的侦查兵种，普通作战和运输部队不能借侦查消耗守军。 */
public final class ReconRules {
    private static final Set<String> UNITS = Set.of("scout", "recon_car");

    private ReconRules() {}

    /** 在库存裁剪前检查原始编队，混编及未知兵种必须报错而非静默过滤。 */
    public static String requireUnit(Map<String, Integer> army) {
        String selected = null;
        for (var entry : (army == null ? Map.<String, Integer>of() : army).entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            if (!UNITS.contains(entry.getKey())) {
                throw new IllegalArgumentException("侦查只能派遣侦察机或侦查装甲车，不能携带普通部队或运输机");
            }
            if (selected != null) throw new IllegalArgumentException("每次侦查只能选择空中或地面一种方式，不能混编");
            selected = entry.getKey();
        }
        if (selected == null) throw new IllegalArgumentException("请至少选择一种兵种出征");
        return selected;
    }

    public static String mode(String unit) {
        return "recon_car".equals(unit) ? "land" : "air";
    }
}
