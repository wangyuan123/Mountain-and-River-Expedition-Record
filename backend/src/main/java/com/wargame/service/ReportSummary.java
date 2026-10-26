package com.wargame.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.wargame.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/** 战报摘要保存战果快照，但移除可重新按需读取的逐回合日志正文。 */
public final class ReportSummary {
    private ReportSummary() {}

    /** 卡片只读必要字段，完整历史兵力和结算快照由详情接口提供。 */
    public static String cardJson(String json) {
        if (json == null) return null;
        try {
            Map<String, Object> full = JsonUtil.getMapper().readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
            if (full == null) return null;
            Map<String, Object> card = new LinkedHashMap<>();
            for (String key : new String[]{"subject", "action", "originalAction", "perspective", "attackerName", "defenderName", "fromName", "toName", "fromCoord", "toCoord", "interceptCoord", "win", "attackerWon", "result", "targetType", "cityConquered", "wildConquered", "wildOccupation", "occupationFailure", "intercepted", "attackerContinued", "attackerRetreated", "defenderRetreated", "interceptionOpening", "targetName", "x", "y", "showCityInfo", "detailLevel", "scoutMode", "isCity", "cause", "cityName", "citySlot", "losses", "period"}) {
                if (full.containsKey(key)) card.put(key, full.get(key));
            }
            for (String key : new String[]{"attackerWin", "troopDisposition", "wildOccupationReason", "conquered", "cityTakeoverReason", "targetSea", "sea", "scoutType", "scoutUnit", "reconMode", "sampledAt", "intelligencePhase"}) {
                if (full.containsKey(key)) card.put(key, full.get(key));
            }
            // 侦察摘要也是出征页的情报来源，只保留成功侦察实际获得的守军快照，不读取当前敌军。
            if (Boolean.TRUE.equals(full.get("showCityInfo")) && full.get("army") instanceof Map) card.put("army", full.get("army"));
            return JsonUtil.toJson(card);
        } catch (Exception ignored) { return null; }
    }

    /**
     * 从持久化的原始战报生成摘要。
     * 只裁掉回合日志字段；初始/幸存兵力、攻守身份、将领、资源与战果等历史快照原样保留。
     * @param json 完整战报 JSON
     * @return 摘要 JSON；原文不是合法对象时返回 null，使清理任务跳过该记录
     */
    public static String fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> full = JsonUtil.getMapper().readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
            Map<String, Object> summary = new LinkedHashMap<>(full);
            summary.remove("roundLogs");
            summary.remove("combatLog");
            summary.remove("battleLog");
            summary.remove("logs");
            summary.put("detailsAvailable", false);
            return JsonUtil.toJson(summary);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 旧日志若仍承担兵力恢复职责，保留原文并告警，不能将不完整摘要当作可裁剪凭据。 */
    public static boolean canTrim(String fullJson, String summaryJson, String type) {
        if (fullJson == null || summaryJson == null) return false;
        try {
            var full = JsonUtil.getMapper().readTree(fullJson);
            var summary = JsonUtil.getMapper().readTree(summaryJson);
            if (!full.isObject() || !summary.isObject()) return false;
            if (!JsonUtil.getMapper().readTree(fromJson(fullJson)).equals(summary)) return false;
            if ("battle".equals(type)) {
                for (String field : new String[]{"initialAttacker", "survivorAttacker", "initialDefender", "survivorDefender"}) {
                    if (!summary.path(field).isObject()) return false;
                }
            }
            return true;
        } catch (Exception ignored) { return false; }
    }
}
