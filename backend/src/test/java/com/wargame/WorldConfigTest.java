package com.wargame;

import com.wargame.model.constants.WorldConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldConfigTest {
    @Test
    void banditGarrisonsAndNpcResourceRewardsScaleByCategory() {
        var first = WorldConfig.BANDIT_LEVELS.get(0);
        assertEquals(300, first.army().get("infantry"));
        assertEquals(2400, first.reward().get("food"));
        assertEquals(3600, first.reward().get("steel"));
        assertEquals(1800, first.reward().get("oil"));
        assertEquals(450, first.reward().get("rare"));
        assertEquals(225, first.reward().get("gold"));

        var high = WorldConfig.BANDIT_LEVELS.get(29);
        assertEquals(java.util.Set.of("fighter", "bomber"), high.army().keySet());
        assertEquals(high.army(), WorldConfig.landNpcGarrison(30, 0, 0));
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
        assertEquals(java.util.List.of("寇"), WorldConfig.BANDIT_NAMES);
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
    void seaNpcGarrisonUnlocksShipsAndCarrierAircraftByLevel() {
        assertEquals(java.util.Map.of("sub", 200), WorldConfig.seaNpcGarrison(1));
        assertEquals(java.util.Set.of("sub", "destroyer", "battleship"), WorldConfig.seaNpcGarrison(20).keySet());
        assertTrue(WorldConfig.seaNpcGarrison(20).values().stream().mapToInt(Integer::intValue).sum() > 0);
        assertFalse(WorldConfig.seaNpcGarrison(20).containsKey("fighter"));
        assertTrue(com.wargame.model.constants.SeaNpcGarrison.formations(21).stream()
                .anyMatch(army -> army.keySet().containsAll(java.util.Set.of("carrier", "fighter"))));
        assertTrue(com.wargame.model.constants.SeaNpcGarrison.formations(23).stream()
                .anyMatch(army -> army.keySet().containsAll(java.util.Set.of("carrier", "fighter", "bomber"))));
        assertTrue(WorldConfig.seaNpcGarrison(30).values().stream().mapToInt(Integer::intValue).sum() > 0);
        assertThrows(IllegalArgumentException.class, () -> WorldConfig.seaNpcGarrison(31));
    }
}
