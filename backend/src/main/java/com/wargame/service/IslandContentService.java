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

    /** 岛屿资源按版本一次性补齐四大战场；旧版本只补新增象限，保留既有归属和战斗结果。 */
    @Transactional
    public void ensure(Long worldId) {
        var world = worlds.lockById(worldId).orElseThrow();
        int version = world.getIslandContentVersion() == null ? 0 : world.getIslandContentVersion();
        if (version >= 2) return;
        String mask = terrain.ensure();
        Set<String> used = terrain.occupiedCoordinates(worldId);
        players.findAll().forEach(player -> {
            if (player.getCityPosX() == null || player.getCityPosY() == null) return;
            int x = WorldTerrainService.anchor(player.getCityPosX(), 2);
            int y = WorldTerrainService.anchor(player.getCityPosY(), 2);
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) used.add((x + dx) + "," + (y + dy));
        });

        for (int row = 0; row < 2; row++) for (int column = 0; column < 2; column++) {
            if (version >= 1 && row == 0 && column == 0) continue;
            seedBattlefield(worldId, mask, used, column, row);
        }
        world.setIslandContentVersion(2);
        worlds.save(world);
    }

    /** 每个战场独立生成镜像岛屿资源，占用集合与全图建城校验保持一致。 */
    private void seedBattlefield(Long worldId, String mask, Set<String> used, int column, int row) {
        int index = 0;
        for (List<Integer> island : WorldTerrainService.islandCells(mask)) {
            List<int[]> available = new ArrayList<>();
            for (int cell : island) {
                int x = cell % WorldConfig.SIZE, y = cell / WorldConfig.SIZE;
                // 岛屿格使用原底图索引，映射后的目标坐标属于各自独立战场。
                if (column == 1) x = WorldConfig.WORLD_SIZE - 1 - x;
                if (row == 1) y = WorldConfig.WORLD_SIZE - 1 - y;
                if (!used.contains(x + "," + y)) available.add(new int[]{x, y});
            }
            // 固定岛屿随机种子，持久化版本保证重启不重复增加资源。
            java.util.Collections.shuffle(available, new Random(7103L + index * 97L));
            int targetCount = Math.min(available.size(), island.size() >= 35 ? 4 : island.size() >= 12 ? 2 : 1);
            for (int target = 0; target < targetCount; target++) {
                int[] cell = available.get(target);
                int x = cell[0], y = cell[1];
                String type = target == targetCount - 1 && targetCount >= 4
                        ? WILDS.get(index % WILDS.size()) : RESOURCES.get((index + target) % RESOURCES.size());
                seedWild(worldId, x, y, type, index + target);
                used.add(x + "," + y);
            }
            index++;
        }
    }

    private void seedWild(Long worldId, int x, int y, String type, int seed) {
        int level = 2 + Math.floorMod(seed * 7, 7);
        WildTile wild = new WildTile();
        wild.setWorldId(worldId);
        wild.setType(type);
        wild.setX(x);
        wild.setY(y);
        wild.setLevel(level);
        wild.setGarrison(JsonUtil.toJson(WorldConfig.wildGarrison(level, WorldTerrainService.sea(terrain.current(), x, y))));
        wild.setScouted(false);
        wild.setOccupied(false);
        wild.setTotalRes(level * com.wargame.model.constants.WorldConfig.RES_PER_WILD_LEVEL);
        wild.setMined(0);
        wilds.save(wild);
    }
}
