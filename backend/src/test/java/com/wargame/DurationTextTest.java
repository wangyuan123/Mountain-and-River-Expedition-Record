package com.wargame;

import com.wargame.util.DurationText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationTextTest {
    @Test
    void omitsOnlyLeadingZeroUnitsAndKeepsLowerUnits() {
        long[] seconds = {0, 1, 59, 60, 61, 3599, 3600, 3605, 3665, 86399, 86400, 86405, 90061, 180501};
        String[] expected = {"0秒", "1秒", "59秒", "1分钟0秒", "1分钟1秒", "59分钟59秒",
                "1小时0分钟0秒", "1小时0分钟5秒", "1小时1分钟5秒", "23小时59分钟59秒",
                "1天0小时0分钟0秒", "1天0小时0分钟5秒", "1天1小时1分钟1秒", "2天2小时8分钟21秒"};
        for (int index = 0; index < seconds.length; index++) {
            assertEquals(expected[index], DurationText.seconds(seconds[index]));
        }
        assertEquals("0秒", DurationText.seconds(-1));
        assertEquals("0秒", DurationText.seconds(Long.MIN_VALUE));
    }

    @Test
    void roundsPositiveMillisecondsUpWithoutOverflow() {
        assertEquals("0秒", DurationText.millis(-1));
        assertEquals("0秒", DurationText.millis(0));
        assertEquals("1秒", DurationText.millis(1));
        assertEquals("1分钟0秒", DurationText.millis(59001));
        assertEquals("1分钟0秒", DurationText.millis(60000));
        assertEquals("1分钟1秒", DurationText.millis(60001));
        assertEquals("1小时0分钟0秒", DurationText.millis(3599001));
        assertEquals("1天0小时0分钟0秒", DurationText.millis(86399999));
        assertEquals("1天0小时0分钟1秒", DurationText.millis(86400001));
        assertEquals(DurationText.seconds(Long.MAX_VALUE / 1000 + 1), DurationText.millis(Long.MAX_VALUE));
    }
}
