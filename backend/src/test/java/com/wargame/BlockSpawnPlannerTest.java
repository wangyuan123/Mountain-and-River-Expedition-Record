package com.wargame;

import com.wargame.model.entity.Bandit;
import com.wargame.model.entity.NpcCity;
import com.wargame.model.entity.WildTile;
import com.wargame.service.BlockSpawnPlanner;
import com.wargame.service.WorldTerrainService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlockSpawnPlannerTest {
    @Test
    void batchFillsSparseBlocksWithoutAddingToTheCrowdedBlock() {
        Set<String> used = new HashSet<>();
        List<int[]> targets = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            targets.add(new int[]{x, 0});
            used.add(x + ",0");
        }
        var planner = new BlockSpawnPlanner("0".repeat(32 * 32), 32, false, used, targets);
        int[] counts = new int[4];
        for (int n = 0; n < 9; n++) {
            int[] destination = planner.pick(used, null, null);
            assertNotNull(destination);
            counts[destination[1] / 16 * 2 + destination[0] / 16]++;
        }
        assertArrayEquals(new int[]{0, 3, 3, 3}, counts);
        assertEquals(17, used.size());
    }

    @Test
    void coastalBlocksAreComparedByTerrainAreaRatherThanAbsoluteCount() {
        char[] mask = "1".repeat(32 * 32).toCharArray();
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) mask[y * 32 + x] = '0';
        for (int x = 16; x < 32; x++) mask[x] = '0';
        var targets = List.of(new int[]{0, 0}, new int[]{1, 0}, new int[]{2, 0}, new int[]{3, 0}, new int[]{16, 0});
        var used = new HashSet<>(Set.of("0,0", "1,0", "2,0", "3,0", "16,0"));
        var planner = new BlockSpawnPlanner(new String(mask), 32, false, used, targets);
        int[] destination = planner.pick(used, null, null);
        assertNotNull(destination);
        assertTrue(destination[0] < 16 && destination[1] < 16,
                "四个目标占 256 格的块比一个目标占 16 格的海岸块更稀疏");
    }

    @Test
    void blockedSparseBlocksFallBackAndOriginalCellRemainsExcludedPerTarget() {
        Set<String> used = new HashSet<>();
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) used.add(x + "," + y);
        used.remove("2,2");
        used.remove("3,2");
        var planner = new BlockSpawnPlanner("0".repeat(32 * 32), 32, false, used,
                List.of(new int[]{0, 0}, new int[]{1, 0}));
        assertArrayEquals(new int[]{3, 2}, planner.pick(used, 2, 2));
        assertNull(planner.pick(used, 2, 2));
        assertArrayEquals(new int[]{2, 2}, planner.pick(used, null, null));
        assertNull(planner.pick(used, null, null));
    }

    @Test
    void batchRechecksNewNpcSpacingBeforeChoosingTheNextCell() {
        var used = new HashSet<String>();
        var planner = new BlockSpawnPlanner("0".repeat(32 * 32), 32, false, used, List.of());
        List<int[]> selected = new ArrayList<>();
        for (int n = 0; n < 40; n++) {
            int[] next = planner.pick(used, null, null);
            assertNotNull(next);
            for (int[] previous : selected)
                assertTrue(Math.abs(previous[0] - next[0]) > 1 || Math.abs(previous[1] - next[1]) > 1);
            selected.add(next);
            WorldTerrainService.reserveFootprint(used, next[0] - 1, next[1] - 1, 3);
        }
    }

    @Test
    void seaTargetsAndRestrictedSupplyStayWithinTheirAllowedCells() {
        char[] mask = "0".repeat(20 * 20).toCharArray();
        for (int y = 0; y < 20; y++) for (int x = 16; x < 20; x++) mask[y * 20 + x] = '1';
        var used = new HashSet<String>();
        var planner = new BlockSpawnPlanner(new String(mask), 20, true, used, List.of(), List.of(18, 19));
        for (int n = 0; n < 2; n++) {
            int[] destination = planner.pick(used, null, null);
            assertNotNull(destination);
            assertTrue(destination[0] >= 18 && destination[0] <= 19);
            assertEquals(0, destination[1]);
            assertTrue(WorldTerrainService.sea(new String(mask), destination[0], destination[1]));
        }
        assertNull(planner.pick(used, null, null));
    }

    @Test
    void hiddenTargetsDoNotCountAsDensityAndClaimedWildsStillDo() {
        NpcCity city = new NpcCity(); city.setX(1); city.setY(1); city.setDefeated(false);
        Bandit active = new Bandit(); active.setX(2); active.setY(2); active.setDefeated(false);
        Bandit defeated = new Bandit(); defeated.setX(3); defeated.setY(3); defeated.setDefeated(true);
        assertEquals(2, BlockSpawnPlanner.npcPositions(List.of(city), List.of(active, defeated)).size());
        WildTile claimed = new WildTile(); claimed.setX(4); claimed.setY(4); claimed.setOccupied(true);
        WildTile hidden = new WildTile(); hidden.setX(5); hidden.setY(5); hidden.setOccupied(false); hidden.setDepletedAt(1L);
        assertEquals(1, BlockSpawnPlanner.wildPositions(List.of(claimed, hidden)).size());
    }
}
