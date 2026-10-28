package com.wargame.service;

import java.util.*;

/** 协防分享单独使用公开字段白名单，新增内部字段不会因透传自动外泄。 */
public final class JointBattleProjection {
    private JointBattleProjection() {}
    public static Map<String, Object> publicReport(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("type", "perspective", "attackerWin", "win", "subject", "targetType", "action", "attackerName", "defenderName",
                "toName", "initialAttacker", "initialDefender", "survivorAttacker", "survivorDefender", "losses", "recoveredCount", "recoveryPercent",
                "wall", "terminalReason", "cityConquered", "cityTakeoverReason", "capturedOfficerName", "report", "roundLogs", "formationContributions", "formationEvents")) {
            if (source.containsKey(key)) result.put(key, source.get(key));
        }
        // 嵌套明细也使用白名单，未来增加内部字段时分享凭证不会自动获得读取权。
        if (result.containsKey("formationContributions")) result.put("formationContributions", rows(result.get("formationContributions"),
                Set.of("formationId", "formation", "kind", "unit", "initial", "survivors", "losses", "damage", "wounded", "unitDamage", "wallDamage", "fortDamage")));
        if (result.containsKey("formationEvents")) result.put("formationEvents", rows(result.get("formationEvents"),
                Set.of("type", "round", "batch", "formationId", "formation", "unit", "countBefore", "from", "to", "firstCombatRound",
                        "reason", "target", "targetFormationId", "targetUnit", "targetCountBefore", "rawDamage", "damage", "skillWindow", "distance", "losses", "survivors", "text")));
        return result;
    }

    private static List<Map<String, Object>> rows(Object value, Set<String> allowed) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : list) if (entry instanceof Map<?, ?> row) {
            Map<String, Object> projected = new LinkedHashMap<>();
            for (String key : allowed) {
                Object field = row.get(key);
                if (field instanceof String || field instanceof Number || field instanceof Boolean) projected.put(key, field);
            }
            result.add(projected);
        }
        return result;
    }
}
