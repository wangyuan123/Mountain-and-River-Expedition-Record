package com.wargame;

import com.wargame.service.PoliticalWordFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PoliticalWordFilterTest {

    private final PoliticalWordFilter filter = new PoliticalWordFilter();

    @Test
    public void masksDirectAndSeparatedTerms() {
        assertEquals("**、***、*-*", filter.filter("六四、法轮功、台-独"));
    }

    @Test
    public void masksTraditionalVariantsWithFullWidthSeparators() {
        assertEquals("*－*与***", filter.filter("臺－獨与法輪功"));
    }

    @Test
    public void keepsOrdinaryTextAndMarkupAsText() {
        assertEquals("台湾地图<script>123</script>", filter.filter("台湾地图<script>123</script>"));
    }
}
