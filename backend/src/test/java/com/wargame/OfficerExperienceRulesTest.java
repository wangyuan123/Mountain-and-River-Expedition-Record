package com.wargame;

import com.wargame.model.constants.OfficerExperienceRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficerExperienceRulesTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/officer-experience.csv", numLinesToSkip = 1)
    void keepsExpectedCostsAtCurveBoundaries(int level, long needed, long cumulative) {
        assertEquals(needed, OfficerExperienceRules.expNeeded(level));
        assertEquals(cumulative, cumulativeToLevel(level));
    }

    @Test
    void costsIncreaseSmoothlyAndReachExpectedTotal() {
        long total = 0;
        long previous = 0;
        for (int level = 1; level < OfficerExperienceRules.MAX_LEVEL; level++) {
            long need = OfficerExperienceRules.expNeeded(level);
            assertTrue(need > previous, "cost must increase at level " + level);
            total += need;
            previous = need;
        }
        assertEquals(38000L, cumulativeToLevel(20));
        assertEquals(886625L, cumulativeToLevel(50));
        assertEquals(7181000L, cumulativeToLevel(80));
        assertEquals(20355750L, total);
    }

    @Test
    void preservesEarlyCostsAndAvoidsJumpsAtStageBoundaries() {
        for (int level = 1; level <= 20; level++) {
            assertEquals(200L * level, OfficerExperienceRules.expNeeded(level));
        }
        for (int level = 2; level < OfficerExperienceRules.MAX_LEVEL - 1; level++) {
            long previousIncrease = OfficerExperienceRules.expNeeded(level) - OfficerExperienceRules.expNeeded(level - 1);
            long nextIncrease = OfficerExperienceRules.expNeeded(level + 1) - OfficerExperienceRules.expNeeded(level);
            assertTrue(nextIncrease >= previousIncrease);
            assertTrue(nextIncrease - previousIncrease <= 1050L);
        }
    }

    private long cumulativeToLevel(int targetLevel) {
        long total = 0;
        for (int level = 1; level < targetLevel; level++) {
            total += OfficerExperienceRules.expNeeded(level);
        }
        return total;
    }
}
