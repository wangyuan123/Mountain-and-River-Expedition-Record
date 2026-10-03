package com.wargame.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 城市和军官各自每日一次改名，按北京时间自然日重置。 */
public final class NameChangePolicy {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private NameChangePolicy() {}

    /**
     * 返回本次改名后的下一次可用时间；当天未改名时返回 0。
     * @param renamedAt 上次成功改名的毫秒时间戳
     * @param now 当前毫秒时间戳
     * @return 下一个北京时间零点的毫秒时间戳，或 0
     */
    public static long nextAllowedAt(long renamedAt, long now) {
        if (renamedAt <= 0) return 0L;
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate();
        LocalDate changed = Instant.ofEpochMilli(renamedAt).atZone(ZONE).toLocalDate();
        return changed.isBefore(today) ? 0L : changed.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }
}
