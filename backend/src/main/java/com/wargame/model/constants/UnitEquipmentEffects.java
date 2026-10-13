package com.wargame.model.constants;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 数值和物品标识冻结在既有科技快照的独立命名空间中；旧快照缺字段等同空配装。 */
public final class UnitEquipmentEffects {
    public static final String PREFIX = "@ue:";
    public static final int VERSION = 1;
    private UnitEquipmentEffects() {}
    public static String base(String id) { return id == null ? "" : id.split("~", 2)[0]; }
    public static String key(String unit, String effect) { return PREFIX + unit + ":" + effect; }
    /** 路线确认令牌按有序快照计算，避免普通 Map.hashCode 的加和碰撞接受旧配装。 */
    public static String actionVersion(Map<String, Integer> snapshot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(new TreeMap<>(snapshot).toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static Map<String, Integer> snapshot(Map<String, Map<String, String>> loadouts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        loadouts.forEach((unit, slots) -> {
            out.put(key(unit, "version"), VERSION);
            slots.values().forEach(item -> {
                UnitEquipmentDef d = UnitEquipmentDef.ITEMS.get(item);
                if (d == null || !d.unit().equals(unit)) return;
                out.put(key(unit, "item:" + item), 1);
                d.effects().forEach((effect, value) -> out.merge(key(unit, effect), value, Integer::sum));
            });
        });
        return out;
    }
    public static double percent(Map<String, Integer> snapshot, String unit, String effect) {
        return snapshot == null ? 0 : snapshot.getOrDefault(key(unit, effect), 0) / 100.0;
    }
    public static double mul(Map<String, Integer> snapshot, String unit, String effect) {
        double value = percent(snapshot, unit, effect);
        double cap = switch (effect) { case "defense", "hp" -> .20; case "load" -> .25; default -> .15; };
        if ("oil".equals(effect)) return 1 + Math.max(-.20, value);
        return 1 + Math.min(cap, value);
    }
    public static double speed(Map<String, Integer> snapshot, String unit, boolean battle) {
        return Math.max(.75, 1 + Math.min(.15, percent(snapshot, unit, "speed") + percent(snapshot, unit, battle ? "battleSpeed" : "marchSpeed")));
    }
    public static double attack(Map<String, Integer> snapshot, String unit, String target) {
        String domain = switch (BattleRules.domain(target)) { case "air" -> "air"; case "sea" -> "sea"; case "fort" -> "fort"; default -> "ground"; };
        return 1 + Math.min(.15, percent(snapshot, unit, domain) + percent(snapshot, unit, "against_" + base(target)));
    }
    public static double damage(Map<String, Integer> snapshot, String target, String attacker) {
        return 1 - Math.min(.20, Math.max(0, percent(snapshot, target, "reduce_" + base(attacker))));
    }
    /** 只公开实际参与编队的装备；其他兵种配装与账号库存不属于本场情报。 */
    public static Map<String, Integer> forArmy(Map<String, Integer> snapshot, Map<String, Integer> army) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (snapshot == null || army == null) return out;
        snapshot.forEach((k, v) -> {
            if (!k.startsWith(PREFIX) || army.entrySet().stream().anyMatch(e -> e.getValue() > 0 && k.startsWith(key(e.getKey(), "")))) out.put(k, v);
        });
        return out;
    }

    public static Map<String, Integer> only(Map<String, Integer> tech) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (tech != null) tech.forEach((k, v) -> { if (k.startsWith(PREFIX)) out.put(k, v); });
        return out;
    }
    /** 按基础兵种合并伤兵、战俘及真实库存，内部配置分组不会泄漏到兵种记录。 */
    public static Map<String, Integer> aggregate(Map<String, Integer> army) {
        Map<String, Integer> out = new LinkedHashMap<>();
        army.forEach((id, n) -> out.merge(base(id), n, Integer::sum));
        return out;
    }
    public static Map<String, Integer> profile(Map<String, Integer> snapshot, String unit) {
        Map<String, Integer> out = new TreeMap<>();
        String prefix = key(unit, "");
        snapshot.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k.substring(prefix.length()), v); });
        return out;
    }
    public static void copyProfile(Map<String, Integer> out, String alias, Map<String, Integer> profile) {
        profile.forEach((k, v) -> out.put(key(alias, k), v));
    }
}
