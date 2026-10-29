package com.wargame.model.constants;

public final class StratagemRules {
    public static final long MIN_MARCH_REMAINING_MS = 30_000L;
    public static final long MIN_WAR_PREPARATION_MS = 30 * 60_000L;

    private StratagemRules() {}

    public static double acceleration(int knowledge) {
        return accelerationPermille(knowledge) / 1000.0;
    }

    public static double delay(int knowledge) {
        return delayPermille(knowledge) / 1000.0;
    }

    public static long acceleratedRemaining(long remaining, int knowledge) {
        return acceleratedRemaining(remaining, knowledge, MIN_MARCH_REMAINING_MS);
    }

    public static long acceleratedRemaining(long remaining, int knowledge, long minRemaining) {
        return Math.max(minRemaining, ceilPermille(remaining, 1000 - accelerationPermille(knowledge)));
    }

    /** 情报行军令以档位决定基础缩时比例，学识最多额外提高三十个百分点。 */
    public static long acceleratedMarchRemaining(long remaining, int knowledge, long minRemaining, String itemKey) {
        int base = ItemDef.MARCH_BONUS_PERCENT.get(itemKey) * 10;
        return Math.max(minRemaining, ceilPermille(remaining, 1000 - base - Math.min(300, Math.max(0, knowledge))));
    }

    public static long delayedRemaining(long remaining, int knowledge) {
        return Math.addExact(remaining, ceilPermille(remaining, delayPermille(knowledge)));
    }

    public static long warStart(long now, long originalStart, long declaredAt, int knowledge) {
        long remaining = originalStart - now;
        return Math.max(now + ceilPermille(remaining, 1000 - accelerationPermille(knowledge)),
                declaredAt + MIN_WAR_PREPARATION_MS);
    }

    private static int accelerationPermille(int knowledge) {
        return 500 + Math.min(300, Math.max(0, knowledge));
    }

    private static int delayPermille(int knowledge) {
        return 500 + Math.min(200, Math.max(0, knowledge));
    }

    private static long ceilPermille(long duration, int permille) {
        return Math.addExact(Math.multiplyExact(duration / 1000, permille),
                (duration % 1000 * permille + 999) / 1000);
    }
}
