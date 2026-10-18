package com.wargame;

import com.wargame.service.IslandContentService;
import com.wargame.service.WorldTerrainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IslandContentServiceTest extends BaseServiceTest {
    @Autowired IslandContentService islands;
    @Autowired WorldTerrainService terrain;

    @Test void addsVariedOffshoreTargetsOnlyOnceWithoutOverwritingExistingLand() {
        var world = createTestWorld();
        String mask = terrain.ensure();
        var groups = WorldTerrainService.islandCells(mask);
        assertEquals(22, groups.size());
        assertTrue(groups.stream().allMatch(group -> !group.isEmpty()));
        assertTrue(groups.stream().mapToInt(java.util.List::size).min().orElseThrow() < 60);
        assertTrue(groups.stream().mapToInt(java.util.List::size).max().orElseThrow() > 60);

        int first = groups.get(0).get(0), x = first % WorldTerrainService.SIZE, y = first / WorldTerrainService.SIZE;
        var existing = createWildTile(world.getId(), "forest", x, y, 1, Map.of(), 100);
        islands.ensure(world.getId());
        var wilds = wildTileRepository.findByWorldId(world.getId());
        var npcs = npcCityRepository.findByWorldId(world.getId());
        assertTrue(wilds.size() > 20);
        assertTrue(npcs.isEmpty(), "岛屿不再生成 NPC 城市/要塞");
        assertEquals(1, wilds.stream().filter(wild -> wild.getId().equals(existing.getId())).count());
        assertEquals(1, wilds.stream().filter(wild -> wild.getX() == x && wild.getY() == y).count());
        Set<String> types = new HashSet<>();
        wilds.forEach(wild -> {
            types.add(wild.getType());
            assertFalse(WorldTerrainService.sea(mask, wild.getX(), wild.getY()));
        });
        assertTrue(types.containsAll(Set.of("oil", "ironworks", "grainfield")));
        npcs.forEach(npc -> assertFalse(WorldTerrainService.sea(mask, npc.getX(), npc.getY())));
        for (int row = 0; row < 2; row++) for (int column = 0; column < 2; column++) {
            int minX = column * WorldTerrainService.SIZE, minY = row * WorldTerrainService.SIZE;
            assertTrue(wilds.stream().filter(w -> w.getX() >= minX && w.getX() < minX + WorldTerrainService.SIZE
                    && w.getY() >= minY && w.getY() < minY + WorldTerrainService.SIZE).count() > 20);
        }
        assertEquals(2, worldMapRepository.findById(world.getId()).orElseThrow().getIslandContentVersion());
        islands.ensure(world.getId());
        assertEquals(wilds.size(), wildTileRepository.findByWorldId(world.getId()).size());
        assertEquals(npcs.size(), npcCityRepository.findByWorldId(world.getId()).size());
    }

    @Test void versionOneOnlyAddsOuterIslandTargetsAndPreservesClaimedLand() {
        var world = createTestWorld();
        world.setIslandContentVersion(1); worldMapRepository.save(world);
        String mask = terrain.ensure();
        int cell = WorldTerrainService.islandCells(mask).get(0).get(0);
        var claimed = createWildTile(world.getId(), "oil", cell % WorldTerrainService.SIZE,
                cell / WorldTerrainService.SIZE, 5, Map.of(), 800);
        claimed.setOccupied(true); claimed.setOccupiedBy(42L); wildTileRepository.saveAndFlush(claimed);
        islands.ensure(world.getId());
        var targets = wildTileRepository.findByWorldId(world.getId());
        assertTrue(targets.stream().filter(w -> w.getX() < 400 && w.getY() < 400)
                .allMatch(w -> w.getId().equals(claimed.getId())));
        assertTrue(wildTileRepository.findById(claimed.getId()).orElseThrow().getOccupied());
        assertEquals(42L, wildTileRepository.findById(claimed.getId()).orElseThrow().getOccupiedBy());
        assertTrue(targets.size() > 60);
        islands.ensure(world.getId());
        assertEquals(targets.size(), wildTileRepository.findByWorldId(world.getId()).size());
    }
}
