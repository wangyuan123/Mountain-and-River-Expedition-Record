package com.wargame.util;

/** 前后端统一的天、小时、分钟、秒展示规则，仅省略前导零单位。 */
public final class DurationText {
    private DurationText() {}

    /** 秒数归零后显示0秒，最高有效单位之后的零单位始终保留。 */
    public static String seconds(long seconds) {
        long total = Math.max(0, seconds);
        long days = total / 86400;
        long hours = total % 86400 / 3600;
        long minutes = total % 3600 / 60;
        StringBuilder text = new StringBuilder();
        if (days > 0) text.append(days).append("天");
        if (days > 0 || hours > 0) text.append(hours).append("小时");
        if (days > 0 || hours > 0 || minutes > 0) text.append(minutes).append("分钟");
        return text.append(total % 60).append("秒").toString();
    }

    /** 正数毫秒向上取整到秒，避免在截止前提前展示0秒。 */
    public static String millis(long milliseconds) {
        long total = Math.max(0, milliseconds);
        return seconds(total / 1000 + (total % 1000 > 0 ? 1 : 0));
    }
}
