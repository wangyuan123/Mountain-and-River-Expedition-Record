package com.wargame.service.bot;

import java.util.Map;

public record BotAction(String type, String subject, Long targetId, Map<String, Integer> army,
                        int amount, String reason) {
    public static BotAction simple(String type, String subject, String reason) {
        return new BotAction(type, subject, null, Map.of(), 0, reason);
    }

    public static BotAction target(String type, String kind, long targetId, Map<String, Integer> army, String reason) {
        return new BotAction(type, kind, targetId, army, 0, reason);
    }
}
