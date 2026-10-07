package com.wargame;

import com.wargame.model.constants.WorldConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldConfigTest {
    @Test
    void banditGarrisonsAndNpcResourceRewardsScaleByCategory() {
        var first = WorldConfig.BANDIT_LEVELS.get(0);
        assertEquals(600, first.army().get("infantry"));
        assertEquals(2400, first.reward().get("food"));
        assertEquals(3600, first.reward().get("steel"));
        assertEquals(1800, first.reward().get("oil"));
        assertEquals(450, first.reward().get("rare"));
        assertEquals(225, first.reward().get("gold"));

        var high = WorldConfig.BANDIT_LEVELS.get(29);
        assertEquals(5400, high.army().get("htank"));
        assertEquals(16200, high.army().get("infantry"));
        assertEquals(2700, high.army().get("rocket"));
        assertEquals(1350, high.army().get("special"));
        assertEquals(144000, high.reward().get("food"));
        assertEquals(216000, high.reward().get("steel"));
        assertEquals(126000, high.reward().get("oil"));
        assertEquals(60750, high.reward().get("rare"));
        assertEquals(27000, high.reward().get("gold"));
        assertFalse(high.reward().containsKey("diamond"));
        assertEquals(high.reward(), WorldConfig.seaNpcReward(30));
        assertEquals(first.reward(), WorldConfig.seaNpcReward(1));
        for (int level = 1; level <= WorldConfig.MAX_NPC_LEVEL; level++) {
            assertEquals(WorldConfig.BANDIT_LEVELS.get(level - 1).reward(), WorldConfig.npcReward(level));
            assertEquals(WorldConfig.BANDIT_LEVELS.get(level - 1).reward(), WorldConfig.seaNpcReward(level));
        }
        assertTrue(WorldConfig.BANDIT_NAMES.stream().anyMatch(name -> name.contains("雇佣兵")));
        for (var tier : WorldConfig.BANDIT_LEVELS) {
            assertFalse(tier.reward().containsKey("exp"));
            assertFalse(tier.reward().containsKey("diamond"));
            assertFalse(tier.army().keySet().stream().anyMatch(key -> java.util.Set.of("destroyer", "sub", "battleship", "carrier").contains(key)));
        }
        assertEquals(java.util.Map.of("infantry", 1), WorldConfig.landOnlyArmy(
                java.util.Map.of("infantry", 1, "destroyer", 2, "sub", 2, "battleship", 2, "carrier", 2)));
    }

    @Test
    void levelThirtyNpcDiamondDropHasOneWinningRollOutOfTen() {
        int winningRolls = 0;
        for (int roll = 0; roll < 10; roll++) {
            int drop = WorldConfig.npcDiamondDrop(30, roll);
            if (drop > 0) winningRolls++;
            assertEquals(0, WorldConfig.npcDiamondDrop(29, roll));
        }
        assertEquals(1, winningRolls);
        assertEquals(5, WorldConfig.npcDiamondDrop(30, 0));
        assertThrows(IllegalArgumentException.class, () -> WorldConfig.npcDiamondDrop(30, 10));
    }

    @Test
    void npcArmyUnlocksByLevelAndMatchesPlannedTotals() {
        int[] totals = {600, 1320, 1800, 2145, 2520, 3330, 4128, 5270, 6498, 7866};
        for (int level = 1; level <= 10; level++) {
            var army = WorldConfig.BANDIT_LEVELS.get(level - 1).army();
            assertEquals(totals[level - 1], army.values().stream().mapToInt(Integer::intValue).sum(), "level " + level);
            if (level <= 5) assertTrue(java.util.Set.of("infantry", "motor").containsAll(army.keySet()));
            if (level <= 8) assertTrue(java.util.Set.of("infantry", "motor", "armored", "ltank", "assault").containsAll(army.keySet()));
        }
        assertEquals(144, WorldConfig.BANDIT_LEVELS.get(8).army().get("htank"));
        assertFalse(WorldConfig.BANDIT_LEVELS.get(8).army().containsKey("rocket"));
        assertEquals(152, WorldConfig.BANDIT_LEVELS.get(9).army().get("rocket"));
        assertFalse(WorldConfig.BANDIT_LEVELS.get(9).army().containsKey("fighter"));
        assertEquals(17730, WorldConfig.BANDIT_LEVELS.get(10).army().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(48600, WorldConfig.BANDIT_LEVELS.get(29).army().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void seaNpcGarrisonUnlocksShipsAndCarrierAircraftByLevel() {
        assertEquals(java.util.Map.of("sub", 400), WorldConfig.seaNpcGarrison(1));
        assertEquals(33176, WorldConfig.seaNpcGarrison(20).values().stream().mapToInt(Integer::intValue).sum());
        assertFalse(WorldConfig.seaNpcGarrison(20).containsKey("fighter"));
        var level21 = WorldConfig.seaNpcGarrison(21);
        assertEquals(100, level21.get("carrier"));
        assertEquals(2400, level21.get("fighter"));
        assertEquals(1200, level21.get("bomber"));
        assertEquals(40300, level21.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(204, WorldConfig.seaNpcGarrison(22).get("carrier"));
        assertEquals(122236, WorldConfig.seaNpcGarrison(30).values().stream().mapToInt(Integer::intValue).sum());
        assertThrows(IllegalArgumentException.class, () -> WorldConfig.seaNpcGarrison(31));
    }
}
