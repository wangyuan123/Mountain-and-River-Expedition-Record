package com.wargame;

import com.wargame.model.constants.StratagemRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StratagemRulesTest {
    @Test void knowledgeAndCaps() {
        assertEquals(0.5, StratagemRules.acceleration(-1), 0.000001);
        assertEquals(0.65, StratagemRules.acceleration(150), 0.000001);
        assertEquals(0.8, StratagemRules.acceleration(300), 0.000001);
        assertEquals(0.8, StratagemRules.acceleration(900), 0.000001);
        assertEquals(0.7, StratagemRules.delay(300), 0.000001);
        assertEquals(510_000, StratagemRules.delayedRemaining(300_000, 300));
    }

    @Test void floorsApplyToMarchAndTotalWarPreparation() {
        assertEquals(30_000, StratagemRules.acceleratedRemaining(60_000, 300));
        assertEquals(1_800_000, StratagemRules.warStart(0, 7_200_000, 0, 300));
        assertEquals(4_320_000, StratagemRules.warStart(3_600_000, 7_200_000, 0, 300));
    }

    @Test void integerRoundingNeverAddsAnExtraMillisecondAtWholeBoundaries() {
        assertEquals(120_000, StratagemRules.acceleratedRemaining(600_000, 300));
        assertEquals(120_001, StratagemRules.acceleratedRemaining(600_001, 300));
        assertEquals(1_020_000, StratagemRules.delayedRemaining(600_000, 300));
        assertEquals(1_020_002, StratagemRules.delayedRemaining(600_001, 300));
        assertEquals(0.8, StratagemRules.acceleration(Integer.MAX_VALUE));
        assertEquals(0.7, StratagemRules.delay(Integer.MAX_VALUE));
    }
}
