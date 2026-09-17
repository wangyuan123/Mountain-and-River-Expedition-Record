package com.wargame.service.compliance;

import com.wargame.config.ComplianceProperties;
import org.springframework.stereotype.Service;
import java.time.*;

/** 统一北京时间的半开区间；节假日覆盖范围外绝不推测开放日期。 */
@Service
public class PlayCalendar {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ComplianceProperties config;
    private final Clock clock;
    public PlayCalendar(ComplianceProperties config, Clock clock) { this.config = config; this.clock = clock; }
    public LocalDate date(long millis) { return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate(); }
    public long at(LocalDate day, int minute) { return day.atStartOfDay(ZONE).plusMinutes(minute).toInstant().toEpochMilli(); }
    public boolean covered(LocalDate day) {
        if (config.isLocalFixtures()) return Math.abs(day.getYear() - LocalDate.now(clock.withZone(ZONE)).getYear()) <= 1;
        try {
            return !config.getCalendarSource().isBlank() && !day.isBefore(LocalDate.parse(config.getCalendarFrom()))
                    && !day.isAfter(LocalDate.parse(config.getCalendarThrough()));
        } catch (RuntimeException e) { return false; }
    }
    public boolean open(LocalDate day) {
        if (!covered(day) || config.getClosedDates().contains(day.toString())) return false;
        return day.getDayOfWeek().getValue() >= 5 || config.getExtraOpenDates().contains(day.toString());
    }
    public long nextWindow(long now) {
        LocalDate day = date(now);
        for (int i = 0; i <= 370; i++, day = day.plusDays(1)) {
            if (!covered(day)) return 0;
            if (open(day) && at(day, 1200) > now) return at(day, 1200);
        }
        return 0;
    }
    /** 救治期限延至原期限之后首个已审核开放窗口末尾；没有覆盖日历时保留原期限。 */
    public long treatmentDeadline(long original) {
        long next = nextWindow(original);
        return next == 0 ? original : Math.max(original, next + 3_600_000L);
    }
}
