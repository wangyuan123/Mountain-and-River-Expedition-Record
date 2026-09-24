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
        assertTrue(groups.stream().mapToInt(java.util.List::size).min().orElseThrow() < 15);
        assertTrue(groups.stream().mapToInt(java.util.List::size).max().orElseThrow() > 60);

        int first = groups.get(0).get(0), x = first % WorldTerrainService.SIZE, y = first / WorldTerrainService.SIZE;
        var existing = createWildTile(world.getId(), "forest", x, y, 1, Map.of(), 100);
        islands.ensure(world.getId());
        var wilds = wildTileRepository.findByWorldId(world.getId());
        var npcs = npcCityRepository.findByWorldId(world.getId());
        assertTrue(wilds.size() > 20);
        assertTrue(npcs.size() > 5);
        assertEquals(1, wilds.stream().filter(wild -> wild.getId().equals(existing.getId())).count());
        assertEquals(1, wilds.stream().filter(wild -> wild.getX() == x && wild.getY() == y).count());
        Set<String> types = new HashSet<>();
        wilds.forEach(wild -> {
            types.add(wild.getType());
            assertFalse(WorldTerrainService.sea(mask, wild.getX(), wild.getY()));
        });
        assertTrue(types.containsAll(Set.of("oil", "ironworks", "grainfield")));
        npcs.forEach(npc -> assertFalse(WorldTerrainService.sea(mask, npc.getX(), npc.getY())));
        assertEquals(1, worldMapRepository.findById(world.getId()).orElseThrow().getIslandContentVersion());
        islands.ensure(world.getId());
        assertEquals(wilds.size(), wildTileRepository.findByWorldId(world.getId()).size());
        assertEquals(npcs.size(), npcCityRepository.findByWorldId(world.getId()).size());
    }
}
