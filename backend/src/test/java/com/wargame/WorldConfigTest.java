package com.wargame;

import com.wargame.model.constants.WorldConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldConfigTest {
    @Test
    void banditGarrisonsAndPlunderScaleWithoutInflatingExperience() {
        var first = WorldConfig.BANDIT_LEVELS.get(0);
        assertEquals(60, first.army().get("infantry"));
        assertEquals(240, first.reward().get("food"));
        assertEquals(45, first.reward().get("gold"));
        assertEquals(15, first.reward().get("exp"));

        var high = WorldConfig.BANDIT_LEVELS.get(29);
        assertEquals(180, high.army().get("htank"));
        assertEquals(10800, high.reward().get("food"));
        assertEquals(1800, high.reward().get("exp"));
        assertTrue(WorldConfig.BANDIT_NAMES.stream().anyMatch(name -> name.contains("雇佣兵")));
        for (var tier : WorldConfig.BANDIT_LEVELS) {
            assertFalse(tier.army().keySet().stream().anyMatch(key -> java.util.Set.of("destroyer", "sub", "battleship", "carrier").contains(key)));
        }
        assertEquals(java.util.Map.of("infantry", 1), WorldConfig.landOnlyArmy(
                java.util.Map.of("infantry", 1, "destroyer", 2, "sub", 2, "battleship", 2, "carrier", 2)));
    }
}
