package com.wargame.model.constants;

import java.util.Map;
import java.util.Set;

/** 四类攻击决定能否交战和基础火力；倍率只描述同一攻击领域内的专项克制。 */
public final class BattleRules {
    public static final String VERSION = "balance-v19-technology-20261016";
    /** 演习共用新移速与玩家距离；规则变更后旧校准不得复用。 */
    public static final String PRACTICE_VERSION = VERSION;
    private static final Set<String> ARTILLERY = Set.of("assault", "rocket", "howitzer", "antitank", "flak");
    /**
     * 反坦克火箭车保留反装甲优势但降为×3，防空装甲车对空降为×2.5，降低爆发并延长交战消耗过程。
     * 其余交战沿用纯属性倍率。
     */
    private static final Map<String, Map<String, Double>> MATCHUPS = Map.of(
            "rocket", Map.of("ltank", 3.0, "htank", 3.0, "armored", 3.0, "recon_car", 3.0, "assault", 3.0),
            "armored", Map.of("fighter", 2.5, "bomber", 2.5));

    private BattleRules() {}

    /** 工事使用独立攻坚火力；特种兵仍按地面单位处理。 */
    public static String domain(String target) {
        target = UnitEquipmentEffects.base(target);
        if ("wall".equals(target) || FortDef.FORTS.containsKey(target)) return "fort";
        UnitDef unit = UnitDef.UNITS.get(target);
        return unit == null ? "land" : unit.branch();
    }

    public static String domainLabel(String target) {
        return switch (domain(target)) { case "air" -> "对空"; case "sea" -> "对海"; case "fort" -> "对工事"; default -> "对地"; };
    }

    /** 特种兵属于地面步兵；火炮单列，避免反坦克与反炮兵混成同一职责。 */
    public static String targetClass(String id) {
        id = UnitEquipmentEffects.base(id);
        if (ARTILLERY.contains(id)) return "artillery";
        UnitDef unit = UnitDef.UNITS.get(id);
        if (unit == null) return "fort";
        return switch (unit.cat()) {
            case "air" -> "air";
            case "arm" -> "armor";
            case "nav" -> "sea";
            default -> "inf";
        };
    }

    /** 仅指定兵种组合享受专项克制，其他交战仍为 1.0。 */
    public static double multiplier(String attacker, String target) {
        return MATCHUPS.getOrDefault(UnitEquipmentEffects.base(attacker), Map.of()).getOrDefault(UnitEquipmentEffects.base(target), 1.0);
    }

    public static boolean ground(String id) {
        String type = targetClass(id);
        return !"air".equals(type) && !"sea".equals(type);
    }
}
