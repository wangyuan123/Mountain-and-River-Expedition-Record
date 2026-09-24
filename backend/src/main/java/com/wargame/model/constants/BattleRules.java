package com.wargame.model.constants;

import java.util.Map;
import java.util.Set;

/** 四类攻击决定能否交战和基础火力；倍率只描述同一攻击领域内的专项克制。 */
public final class BattleRules {
    public static final String VERSION = "balance-v9-rocket-counters-20260923";
    private static final Set<String> ARTILLERY = Set.of("assault", "rocket", "howitzer", "antitank", "flak");
    /** 火箭反装甲、装甲车防空获得专项克制，其余交战沿用纯属性倍率。 */
    private static final Map<String, Map<String, Double>> MATCHUPS = Map.of(
            "rocket", Map.of("ltank", 10.0, "htank", 10.0, "armored", 10.0, "assault", 10.0),
            "armored", Map.of("fighter", 5.0, "bomber", 5.0));

    private BattleRules() {}

    /** 工事使用独立攻坚火力；特种兵仍按地面单位处理。 */
    public static String domain(String target) {
        if (FortDef.FORTS.containsKey(target)) return "fort";
        UnitDef unit = UnitDef.UNITS.get(target);
        return unit == null ? "land" : unit.branch();
    }

    public static String domainLabel(String target) {
        return switch (domain(target)) { case "air" -> "对空"; case "sea" -> "对海"; case "fort" -> "对工事"; default -> "对地"; };
    }

    /** 特种兵属于地面步兵；火炮单列，避免反坦克与反炮兵混成同一职责。 */
    public static String targetClass(String id) {
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
        return MATCHUPS.getOrDefault(attacker, Map.of()).getOrDefault(target, 1.0);
    }

    public static boolean ground(String id) {
        String type = targetClass(id);
        return !"air".equals(type) && !"sea".equals(type);
    }
}
