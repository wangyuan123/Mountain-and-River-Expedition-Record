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
import java.util.List;
import java.util.Map;
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
        if (version >= 2) return;
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
                seedFleet(worldId, x, y, ++fleet);
            }
        }
        }
        // Version 2 adds a distinct small-vessel target to each usable sea region.
        for (int gy = 0; gy < WorldConfig.SIZE; gy += 16) {
            for (int gx = 0; gx < WorldConfig.SIZE; gx += 16) {
                List<Integer> cells = cells(gx, gy, 16, mask, used, true, gy * 1217L + gx);
                if (cells.isEmpty()) continue;
                int cell = cells.get(0), x = cell % WorldConfig.SIZE, y = cell / WorldConfig.SIZE;
                used.add(x + "," + y);
                boolean submarine = ((gx + gy) / 16) % 2 == 0;
                int number = gy / 16 * 13 + gx / 16 + 1;
                seedSmallFleet(worldId, x, y, number, submarine);
            }
        }
        world.setWorldContentVersion(2);
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
        int level = 1 + Math.floorMod(seed, 8);
        WildTile wild = new WildTile();
        wild.setWorldId(worldId); wild.setX(x); wild.setY(y); wild.setType(type); wild.setLevel(level);
        wild.setGarrison(JsonUtil.toJson(Map.of("infantry", 5 * level)));
        wild.setScouted(false); wild.setOccupied(false); wild.setTotalRes(level * 800); wild.setMined(0);
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

    private void seedFleet(Long worldId, int x, int y, int number) {
        boolean carrier = number % 4 == 0;
        int level = carrier ? 6 + number % 5 : 2 + number % 7;
        Bandit npc = new Bandit();
        npc.setWorldId(worldId); npc.setX(x); npc.setY(y); npc.setLevel(level);
        npc.setName("日寇第" + number + (carrier ? "航母编队" : "舰队"));
        // Sea targets use only naval vessels and aircraft; land NPC tiers are never reused here.
        npc.setArmy(JsonUtil.toJson(carrier
                ? Map.of("carrier", 1 + level / 8, "destroyer", 2 + level / 3, "fighter", level * 2, "bomber", level)
                : Map.of("battleship", Math.max(1, level / 4), "destroyer", 2 + level / 2, "sub", Math.max(1, level / 3), "fighter", level)));
        npc.setCommanderName(carrier ? "南云忠一" : "山本五十六"); npc.setDefeated(false);
        bandits.save(npc);
    }

    private void seedSmallFleet(Long worldId, int x, int y, int number, boolean submarine) {
        int level = 2 + number % 6;
        Bandit npc = new Bandit();
        npc.setWorldId(worldId); npc.setX(x); npc.setY(y); npc.setLevel(level);
        npc.setName("日寇第" + number + (submarine ? "潜艇支队" : "驱逐舰队"));
        npc.setArmy(JsonUtil.toJson(submarine
                ? Map.of("sub", 2 + level / 2)
                : Map.of("destroyer", 2 + level, "fighter", level)));
        npc.setCommanderName(submarine ? "山本五十六" : "小泽治三郎");
        npc.setDefeated(false);
        bandits.save(npc);
    }
}
