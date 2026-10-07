package com.wargame.service;

import com.wargame.model.constants.WorldConfig;
import com.wargame.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** 修复旧单格据点扩为四格后的占地冲突，保留身份、守军和战果。 */
@Service
@RequiredArgsConstructor
public class NpcFootprintService {
    private final WorldMapRepository worlds;
    private final WorldTerrainService terrain;
    private final WildTileRepository wilds;
    private final PlayerCityRepository cities;
    private final PlayerRepository players;
    private final NpcCityRepository npcs;
    private final BanditRepository bandits;
    private final MarchRepository marches;

    /** 将已有 NPC 外围的一格间隔加入生成禁区。 */
    public void reserveSpacing(Long worldId, Set<String> used) {
        npcs.findByWorldId(worldId).forEach(n -> reserveSpacing(used, n.getX(), n.getY()));
        bandits.findByWorldId(worldId).forEach(n -> reserveSpacing(used, n.getX(), n.getY()));
    }

    private void reserveSpacing(Set<String> used, int x, int y) {
        for (int dy = -1; dy <= 2; dy++) for (int dx = -1; dx <= 2; dx++)
            used.add((x + dx) + "," + (y + dy));
    }

    /** 在世界锁内迁移冲突目标；行军中的目标保留坐标，等待后续维护。 */
    @Transactional
    public void repair(Long worldId) {
        var world = worlds.lockById(worldId).orElseThrow();
        String mask = terrain.current();
        if (mask == null || mask.isEmpty()) return;
        int size = Math.min(WorldConfig.SIZE, world.getSize() == null ? WorldConfig.SIZE : world.getSize());
        Map<String, Integer> occupied = new HashMap<>();
        wilds.findByWorldId(worldId).forEach(w -> mark(occupied, w.getX(), w.getY(), 1, 1));
        cities.findByWorldId(worldId).forEach(c -> mark(occupied, c.getX(), c.getY(), 2, 1));
        players.findAll().forEach(p -> {
            if (p.getCityPosX() != null && p.getCityPosY() != null)
                mark(occupied, p.getCityPosX(), p.getCityPosY(), 2, 1);
        });
        var npcList = npcs.findByWorldId(worldId);
        var banditList = bandits.findByWorldId(worldId);
        Map<String, Integer> spacing = new HashMap<>();
        npcList.forEach(n -> mark(spacing, n.getX() - 1, n.getY() - 1, 4, 1));
        banditList.forEach(n -> mark(spacing, n.getX() - 1, n.getY() - 1, 4, 1));
        var candidates = new java.util.ArrayList<Integer>(size * size);
        for (int cell = 0; cell < size * size; cell++) candidates.add(cell);
        java.util.Collections.shuffle(candidates);
        npcList.forEach(n -> mark(occupied, n.getX(), n.getY(), 2, 1));
        banditList.forEach(n -> mark(occupied, n.getX(), n.getY(), 2, 1));
        for (var npc : npcList) {
            if (marches.existsByTargetIdAndTargetKindIn(String.valueOf(npc.getId()), java.util.List.of("npc"))) continue;
            relocate(mask, size, occupied, spacing, candidates, npc.getX(), npc.getY(), (x, y) -> {
                npc.setX(x); npc.setY(y); npcs.save(npc);
            });
        }
        for (var npc : banditList) {
            if (marches.existsByTargetIdAndTargetKindIn(String.valueOf(npc.getId()), java.util.List.of("bandit"))) continue;
            relocate(mask, size, occupied, spacing, candidates, npc.getX(), npc.getY(), (x, y) -> {
                npc.setX(x); npc.setY(y); bandits.save(npc);
            });
        }
    }

    private void relocate(String mask, int size, Map<String, Integer> occupied,
                          Map<String, Integer> spacing, java.util.List<Integer> candidates, int x, int y,
                          BiConsumer<Integer, Integer> move) {
        boolean water = WorldTerrainService.sea(mask, x, y);
        // 使用计数而非集合，移除自身时不能误释放其他重叠目标的格子。
        mark(occupied, x, y, 2, -1);
        // 四格据点外围留一格空隙；计数保留其他 NPC 的间距约束。
        mark(spacing, x - 1, y - 1, 4, -1);
        Set<String> used = new HashSet<>(occupied.keySet());
        used.addAll(spacing.keySet());
        int nx = x, ny = y;
        if (!WorldTerrainService.vacantFootprint(mask, size, water, used, x, y, 2)) {
            int start = java.util.concurrent.ThreadLocalRandom.current().nextInt(size * size);
            for (int i = 0; i < size * size; i++) {
                int cell = candidates.get((start + i) % candidates.size()), cx = cell % size, cy = cell / size;
                if (!WorldTerrainService.vacantFootprint(mask, size, water, used, cx, cy, 2)) continue;
                nx = cx; ny = cy; move.accept(nx, ny); break;
            }
        }
        // 空间不足时重新预留旧位置，后续目标不能把它当成空地。
        mark(occupied, nx, ny, 2, 1);
        mark(spacing, nx - 1, ny - 1, 4, 1);
    }

    private void mark(Map<String, Integer> occupied, int x, int y, int span, int delta) {
        for (int dy = 0; dy < span; dy++) for (int dx = 0; dx < span; dx++) {
            String key = (x + dx) + "," + (y + dy);
            int count = occupied.getOrDefault(key, 0) + delta;
            if (count == 0) occupied.remove(key); else occupied.put(key, count);
        }
    }
}
