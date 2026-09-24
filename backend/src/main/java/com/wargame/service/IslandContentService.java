package com.wargame.service;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.WildTile;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.WildTileRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class IslandContentService {
    private static final List<String> RESOURCES = List.of("oil", "ironworks", "grainfield");
    private static final List<String> WILDS = List.of("forest", "hill", "grassland", "plains", "rock");
    private final WorldMapRepository worlds;
    private final WildTileRepository wilds;
    private final PlayerRepository players;
    private final WorldTerrainService terrain;
    private final NpcCitySpawnService npcs;

    /** Seed new island targets once under the world lock; existing ownership and battles are never reset. */
    @Transactional
    public void ensure(Long worldId) {
        var world = worlds.lockById(worldId).orElseThrow();
        if (world.getIslandContentVersion() != null && world.getIslandContentVersion() >= 1) return;
        String mask = terrain.ensure();
        Set<String> used = terrain.occupiedCoordinates(worldId);
        players.findAll().forEach(player -> {
            if (player.getCityPosX() == null || player.getCityPosY() == null) return;
            int x = WorldTerrainService.anchor(player.getCityPosX(), 2);
            int y = WorldTerrainService.anchor(player.getCityPosY(), 2);
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) used.add((x + dx) + "," + (y + dy));
        });

        int index = 0;
        for (List<Integer> island : WorldTerrainService.islandCells(mask)) {
            List<Integer> available = new ArrayList<>();
            for (int cell : island) {
                int x = cell % WorldConfig.SIZE, y = cell / WorldConfig.SIZE;
                if (!used.contains(x + "," + y)) available.add(cell);
            }
            // Fixed placement per island; persistent version prevents defeated NPCs from respawning on restart.
            java.util.Collections.shuffle(available, new Random(7103L + index * 97L));
            int targetCount = Math.min(available.size(), island.size() >= 35 ? 4 : island.size() >= 12 ? 2 : 1);
            for (int target = 0; target < targetCount; target++) {
                int cell = available.get(target), x = cell % WorldConfig.SIZE, y = cell / WorldConfig.SIZE;
                if (target == 0 && island.size() >= 20) {
                    npcs.spawnAt(worldId, x, y);
                } else {
                    String type = target == targetCount - 1 && targetCount >= 4
                            ? WILDS.get(index % WILDS.size()) : RESOURCES.get((index + target) % RESOURCES.size());
                    seedWild(worldId, x, y, type, index + target);
                }
            }
            index++;
        }
        world.setIslandContentVersion(1);
        worlds.save(world);
    }

    private void seedWild(Long worldId, int x, int y, String type, int seed) {
        int level = 2 + Math.floorMod(seed * 7, 7);
        WildTile wild = new WildTile();
        wild.setWorldId(worldId);
        wild.setType(type);
        wild.setX(x);
        wild.setY(y);
        wild.setLevel(level);
        wild.setGarrison(JsonUtil.toJson(Map.of("infantry", 5 * level)));
        wild.setScouted(false);
        wild.setOccupied(false);
        wild.setTotalRes(level * 800);
        wild.setMined(0);
        wilds.save(wild);
    }
}
