package com.wargame;

import com.wargame.model.constants.LandNpcGarrison;
import com.wargame.model.constants.WorldConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LandNpcGarrisonTest {
    @Test
    void everyFormationObeysUnlocksAndForbiddenPairs() {
        for (int level = 1; level <= 30; level++) {
            Set<String> allowed = new HashSet<>(Set.of("infantry"));
            if (level >= 6) allowed.add("armored");
            if (level >= 11) allowed.addAll(Set.of("ltank", "fighter"));
            if (level >= 16) allowed.add("bomber");
            if (level >= 25) allowed.addAll(Set.of("htank", "rocket", "special"));
            Set<String> seen = new HashSet<>();
            for (var army : LandNpcGarrison.formations(level)) {
                assertTrue(allowed.containsAll(army.keySet()), "越级兵种：" + level + " " + army);
                assertTrue(army.values().stream().allMatch(n -> n > 0));
                assertFalse(army.containsKey("rocket") && army.containsKey("special"));
                assertFalse(army.containsKey("htank") && army.containsKey("bomber"));
                if (level >= 25) assertTrue(army.size() >= 2 && army.size() <= 4);
                seen.addAll(army.keySet());
            }
            assertEquals(allowed, seen, "已解锁兵种应在候选编队中出现：" + level);
        }
    }

    @Test
    void strengthGrowsSmoothlyAcrossEveryLevelAndEveryFormation() {
        double previousMin = 0, previousMax = 0;
        for (int level = 1; level <= 30; level++) {
            double min = Double.POSITIVE_INFINITY, max = 0;
            for (var army : LandNpcGarrison.formations(level)) {
                double strength = LandNpcGarrison.strength(army);
                assertTrue(strength <= LandNpcGarrison.budget(level));
                assertTrue(strength >= LandNpcGarrison.budget(level) * .99);
                min = Math.min(min, strength);
                max = Math.max(max, strength);
            }
            if (level > 1) {
                assertTrue(min / previousMax >= 1.18, "最低强度须递增：" + level);
                assertTrue(max / previousMin <= 1.22, "解锁时不得出现额外跃升：" + level);
            }
            previousMin = min;
            previousMax = max;
        }
        assertEquals(Map.of("infantry", 300), LandNpcGarrison.army(1, 0));
        assertEquals(Map.of("infantry", 622), LandNpcGarrison.army(5, 0));
        assertEquals(1548, Math.round(LandNpcGarrison.budget(10)));
        assertEquals(1858, Math.round(LandNpcGarrison.budget(11)));
        assertEquals(59344, Math.round(LandNpcGarrison.budget(30)));
    }

    @Test
    void highLevelsOfferDistinctSpecializedFormationsAndStableSelections() {
        for (int level = 25; level <= 30; level++) {
            var choices = LandNpcGarrison.formations(level);
            assertEquals(6, choices.size());
            assertEquals(6, new HashSet<>(choices).size());
            assertTrue(choices.stream().anyMatch(a -> a.keySet().equals(Set.of("fighter", "bomber"))));
            assertTrue(choices.stream().anyMatch(a -> a.keySet().equals(Set.of("ltank", "rocket"))));
            Set<Map<String, Integer>> selected = new HashSet<>();
            for (int y = 0; y < choices.size(); y++) {
                var army = WorldConfig.landNpcGarrison(level, 17, y);
                assertEquals(army, WorldConfig.landNpcGarrison(level, 17, y));
                selected.add(army);
            }
            assertEquals(new HashSet<>(choices), selected);
            assertTrue(choices.contains(LandNpcGarrison.army(level, Long.MIN_VALUE)));
            assertTrue(choices.contains(LandNpcGarrison.army(level, Long.MAX_VALUE)));
        }
        assertThrows(IllegalArgumentException.class, () -> LandNpcGarrison.formations(0));
        assertThrows(IllegalArgumentException.class, () -> LandNpcGarrison.formations(31));
        assertThrows(UnsupportedOperationException.class,
                () -> LandNpcGarrison.army(30, 0).put("htank", 1));
    }
}
