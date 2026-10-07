package com.wargame.service;

import com.wargame.model.constants.JapaneseOfficers;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.Bandit;
import com.wargame.model.entity.WildTile;
import com.wargame.repository.BanditRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.WildTileRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorldContentService {
    private static final List<String> RESOURCES = List.of("grainfield", "ironworks", "oil", "rarefactory");
    private final WorldMapRepository worlds;
    private final BanditRepository bandits;
    private final WildTileRepository wilds;
    private final PlayerRepository players;
    private final WorldTerrainService terrain;

    /** Apply world content versions without changing existing ownership or defeated state. */
    @Transactional
    public void ensure(Long worldId) {
        var world = worlds.lockById(worldId).orElseThrow();
        int version = world.getWorldContentVersion() == null ? 0 : world.getWorldContentVersion();
        if (version >= 3) return;
        String mask = terrain.ensure();
        Set<String> used = terrain.occupiedCoordinates(worldId);
        reservePlayerCities(used);
        if (version < 1) {
        for (int gy = 0; gy < WorldConfig.SIZE; gy += 16) {
            for (int gx = 0; gx < WorldConfig.SIZE; gx += 16) {
                List<Integer> cells = cells(gx, gy, 16, mask, used, false, gy * 397L + gx);
                for (int n = 0; n < 3 && !cells.isEmpty(); n++) {
                    int cell = cells.remove(cells.size() - 1);
                    int x = cell % WorldConfig.SIZE, y = cell / WorldConfig.SIZE;
                    used.add(x + "," + y);
                    if (n == 0) seedLandNpc(worldId, x, y, gx + gy);
                    else seedWild(worldId, x, y, RESOURCES.get(Math.floorMod(gx / 16 + gy / 16 + n, 4)), gx + gy + n);
                }
            }
        }
        players.findAll().forEach(player -> {
            if (player.isGameInitialized() && player.getCityPosX() != null && player.getCityPosY() != null)
                seedAround(worldId, mask, used, player.getCityPosX(), player.getCityPosY());
        });
        int fleet = 0;
        for (int gy = 0; gy < WorldConfig.SIZE; gy += 16) {
            for (int gx = 0; gx < WorldConfig.SIZE; gx += 16) {
                List<Integer> cells = cells(gx, gy, 16, mask, used, true, gy * 811L + gx);
                if (cells.isEmpty()) continue;
                int cell = cells.get(0), x = cell % WorldConfig.SIZE, y = cell / WorldConfig.SIZE;
                used.add(x + "," + y);
                seedSeaNpc(worldId, x, y, ++fleet);
            }
        }
        }
        // 旧版第二轮海域目标只补一次；版本 2 升级时不能重复布点。
        if (version < 2) {
            for (int gy = 0; gy < WorldConfig.SIZE; gy += 16) {
                for (int gx = 0; gx < WorldConfig.SIZE; gx += 16) {
                    List<Integer> cells = cells(gx, gy, 16, mask, used, true, gy * 1217L + gx);
                    if (cells.isEmpty()) continue;
                    int cell = cells.get(0), x = cell % WorldConfig.SIZE, y = cell / WorldConfig.SIZE;
                    used.add(x + "," + y);
                    int number = gy / 16 * 13 + gx / 16 + 1;
                    seedSeaNpc(worldId, x, y, number + WorldConfig.MAX_NPC_LEVEL);
                }
            }
        }
        // 旧世界只升级现存海洋目标；击败状态与空兵力不回填，避免重启复活。
        int seaIndex = 0;
        for (Bandit npc : bandits.findByWorldId(worldId).stream()
                .filter(b -> WorldTerrainService.sea(mask, b.getX(), b.getY()))
                .sorted(Comparator.comparing(Bandit::getId)).toList()) {
            int level = 1 + seaIndex++ % WorldConfig.MAX_NPC_LEVEL;
            npc.setLevel(level);
            npc.setName("日寇海域守军 Lv." + level);
            if (!Boolean.TRUE.equals(npc.getDefeated()))
                npc.setArmy(JsonUtil.toJson(WorldConfig.seaNpcGarrison(level)));
            bandits.save(npc);
        }
        world.setWorldContentVersion(3);
        worlds.save(world);
    }

    /** Newly founded main cities receive nearby sites even after the global backfill has run. */
    @Transactional
    public void ensureAround(Long worldId, int x, int y) {
        worlds.lockById(worldId).orElseThrow();
        Set<String> used = terrain.occupiedCoordinates(worldId);
        reservePlayerCities(used);
        seedAround(worldId, terrain.ensure(), used, x, y);
    }

    private void reservePlayerCities(Set<String> used) {
        players.findAll().forEach(player -> {
            if (player.getCityPosX() == null || player.getCityPosY() == null) return;
            int x = WorldTerrainService.anchor(player.getCityPosX(), 2);
            int y = WorldTerrainService.anchor(player.getCityPosY(), 2);
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) used.add((x + dx) + "," + (y + dy));
        });
    }

    private void seedAround(Long worldId, String mask, Set<String> used, int x, int y) {
        var nearbyWilds = wilds.findByWorldId(worldId).stream()
                .filter(w -> !Boolean.TRUE.equals(w.getOccupied()))
                .filter(w -> Math.abs(w.getX() - x) + Math.abs(w.getY() - y) <= 7).toList();
        int existingWilds = nearbyWilds.size();
        int innerWilds = (int) nearbyWilds.stream()
                .filter(w -> Math.abs(w.getX() - x) + Math.abs(w.getY() - y) <= 3).count();
        int existingNpcs = (int) bandits.findByWorldId(worldId).stream()
                .filter(b -> !WorldTerrainService.sea(mask, b.getX(), b.getY()))
                .filter(b -> Math.abs(b.getX() - x) + Math.abs(b.getY() - y) <= 7).count();
        List<Integer> nearby = cells(x - 7, y - 7, 15, mask, used, false, x * 1171L + y);
        nearby.removeIf(cell -> Math.abs(cell % WorldConfig.SIZE - x) + Math.abs(cell / WorldConfig.SIZE - y) > 7);
        List<Integer> inner = new ArrayList<>(nearby);
        inner.removeIf(cell -> Math.abs(cell % WorldConfig.SIZE - x) + Math.abs(cell / WorldConfig.SIZE - y) > 3);
        int added = 0;
        for (int n = innerWilds; n < 4 && !inner.isEmpty(); n++) {
            int cell = inner.remove(inner.size() - 1);
            nearby.remove(Integer.valueOf(cell));
            int cx = cell % WorldConfig.SIZE, cy = cell / WorldConfig.SIZE;
            used.add(cx + "," + cy);
            seedWild(worldId, cx, cy, RESOURCES.get(n % 4), x + y + n);
            added++;
        }
        for (int n = existingWilds + added; n < 8 && !nearby.isEmpty(); n++) {
            int cell = nearby.remove(nearby.size() - 1);
            int cx = cell % WorldConfig.SIZE, cy = cell / WorldConfig.SIZE;
            used.add(cx + "," + cy);
            seedWild(worldId, cx, cy, RESOURCES.get(n % 4), x + y + n);
        }
        for (int n = existingNpcs; n < 2 && !nearby.isEmpty(); n++) {
            int cell = nearby.remove(nearby.size() - 1);
            int cx = cell % WorldConfig.SIZE, cy = cell / WorldConfig.SIZE;
            used.add(cx + "," + cy);
            seedLandNpc(worldId, cx, cy, x + y + n);
        }
    }

    private static List<Integer> cells(int x0, int y0, int span, String mask, Set<String> used, boolean water, long seed) {
        List<Integer> result = new ArrayList<>();
        for (int y = Math.max(0, y0); y < Math.min(WorldConfig.SIZE, y0 + span); y++)
            for (int x = Math.max(0, x0); x < Math.min(WorldConfig.SIZE, x0 + span); x++)
                if (WorldTerrainService.sea(mask, x, y) == water && !used.contains(x + "," + y))
                    result.add(y * WorldConfig.SIZE + x);
        Collections.shuffle(result, new Random(seed));
        return result;
    }

    private void seedWild(Long worldId, int x, int y, String type, int seed) {
        int level = 1 + Math.floorMod(seed, WorldConfig.MAX_WILD_LEVEL);
        WildTile wild = new WildTile();
        wild.setWorldId(worldId); wild.setX(x); wild.setY(y); wild.setType(type); wild.setLevel(level);
        wild.setGarrison(JsonUtil.toJson(WorldConfig.wildGarrison(level, WorldTerrainService.sea(terrain.ensure(), x, y))));
        wild.setScouted(false); wild.setOccupied(false); wild.setTotalRes(level * WorldConfig.RES_PER_WILD_LEVEL); wild.setMined(0);
        wilds.save(wild);
    }

    private void seedLandNpc(Long worldId, int x, int y, int seed) {
        int level = 1 + Math.floorMod(seed, 8);
        Bandit npc = new Bandit();
        npc.setWorldId(worldId); npc.setX(x); npc.setY(y); npc.setLevel(level);
        npc.setName(WorldConfig.BANDIT_NAMES.get(Math.floorMod(seed, WorldConfig.BANDIT_NAMES.size())) + " Lv." + level);
        npc.setArmy(JsonUtil.toJson(WorldConfig.BANDIT_LEVELS.get(level - 1).army()));
        npc.setCommanderName(JapaneseOfficers.getCommanderForLevel(level)); npc.setDefeated(false);
        bandits.save(npc);
    }

    /** 两轮海域布点共用等级 1～30 与同一套守军，不再区分舰队类型。 */
    private void seedSeaNpc(Long worldId, int x, int y, int number) {
        int level = 1 + Math.floorMod(number - 1, WorldConfig.MAX_NPC_LEVEL);
        Bandit npc = new Bandit();
        npc.setWorldId(worldId); npc.setX(x); npc.setY(y); npc.setLevel(level);
        npc.setName("日寇海域守军 Lv." + level);
        npc.setArmy(JsonUtil.toJson(WorldConfig.seaNpcGarrison(level)));
        npc.setCommanderName("山本五十六");
        npc.setDefeated(false);
        bandits.save(npc);
    }
}
