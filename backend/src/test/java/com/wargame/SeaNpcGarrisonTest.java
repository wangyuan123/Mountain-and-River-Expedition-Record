package com.wargame;

import com.wargame.model.constants.SeaNpcGarrison;
import com.wargame.model.constants.WorldConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SeaNpcGarrisonTest {
    @Test
    void formationsRespectNavalUnlockStages() {
        for (int level = 1; level <= 30; level++) {
            Set<String> expected = new HashSet<>(Set.of("sub"));
            if (level >= 4) expected.add("destroyer");
            if (level >= 11) expected.add("battleship");
            if (level >= 21) expected.addAll(Set.of("carrier", "fighter", "bomber"));
            Set<String> seen = new HashSet<>();
            for (var army : SeaNpcGarrison.formations(level)) {
                assertTrue(army.values().stream().allMatch(n -> n > 0));
                assertTrue(expected.containsAll(army.keySet()), "越级海军：" + level + " " + army);
                seen.addAll(army.keySet());
            }
            assertEquals(expected, seen, "已解锁海军应在候选编队中出现：" + level);
        }
    }

    @Test
    void everySeaFormationUsesHalfBaselineAndSmoothBudget() {
        double previousMin = 0;
        for (int level = 1; level <= WorldConfig.MAX_NPC_LEVEL; level++) {
            double min = Double.POSITIVE_INFINITY;
            for (var army : SeaNpcGarrison.formations(level)) {
                double strength = SeaNpcGarrison.strength(army);
                assertTrue(strength <= SeaNpcGarrison.budget(level));
                assertTrue(strength >= SeaNpcGarrison.budget(level) * .98,
                        "编队预算偏差过大：Lv." + level + " " + army);
                min = Math.min(min, strength);
            }
            if (level > 1) assertTrue(min / previousMin >= 1.18 && min / previousMin <= 1.22,
                    "相邻等级强度应平滑增长：Lv." + level);
            previousMin = min;
        }
        assertEquals(Map.of("sub", 200), SeaNpcGarrison.army(1, 0));
        assertEquals(200, Math.round(SeaNpcGarrison.budget(1)));
        assertEquals(1_032, Math.round(SeaNpcGarrison.budget(10)));
        assertEquals(39_563, Math.round(SeaNpcGarrison.budget(30)));
    }

    @Test
    void coordinatesSelectStableSpecializedHighLevelFleets() {
        for (int level = 25; level <= 30; level++) {
            var choices = SeaNpcGarrison.formations(level);
            assertEquals(6, choices.size());
            assertEquals(6, new HashSet<>(choices).size());
            Set<Map<String, Integer>> selected = new HashSet<>();
            for (int y = 0; y < choices.size(); y++) {
                var army = WorldConfig.seaNpcGarrison(level, 17, y);
                assertEquals(army, WorldConfig.seaNpcGarrison(level, 17, y));
                selected.add(army);
            }
            assertEquals(new HashSet<>(choices), selected);
            assertTrue(choices.contains(SeaNpcGarrison.army(level, Long.MIN_VALUE)));
            assertTrue(choices.contains(SeaNpcGarrison.army(level, Long.MAX_VALUE)));
        }
    }
}
