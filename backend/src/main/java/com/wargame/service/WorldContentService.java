package com.wargame.service;

import com.wargame.model.constants.JapaneseOfficers;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.Bandit;
import com.wargame.model.entity.NpcCity;
import com.wargame.model.entity.WildTile;
import com.wargame.repository.BanditRepository;
import com.wargame.repository.NpcCityRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorldContentService {
    private static final List<String> RESOURCES = List.of("grainfield", "ironworks", "oil", "rarefactory");
    private final WorldMapRepository worlds;
    private final BanditRepository bandits;
    private final NpcCityRepository npcs;
    private final WildTileRepository wilds;
    private final PlayerRepository players;
    private final WorldTerrainService terrain;

    /** Apply world content versions without changing existing ownership or defeated state. */
    @Transactional
    public void ensure(Long worldId) {
        var world = worlds.lockById(worldId).orElseThrow();
        int version = world.getWorldContentVersion() == null ? 0 : world.getWorldContentVersion();
        if (version >= 3) {
            ensureSeaFleets(worldId);
            return;
        }
        String mask = terrain.ensure();
        Set<String> used = terrain.occupiedCoordinates(worldId);
        reservePlayerCities(used);
        if (version < 1) {
        seedLandBlocks(worldId, mask, used);
        players.findAll().forEach(player -> {
            if (player.isGameInitialized() && player.getCityPosX() != null && player.getCityPosY() != null)
                seedAround(worldId, mask, used, player.getCityPosX(), player.getCityPosY());
        });
        seedSeaBlocks(worldId, mask, used, 0);
        }
        // 旧版第二轮海域目标只补一次；版本 2 升级时不能重复布点。
        if (version < 2) {
            seedSeaBlocks(worldId, mask, used, WorldConfig.MAX_NPC_LEVEL);
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

    /** 确保海洋区域始终具有日寇舰队守军，若海域目标全部缺失则自动补全两轮布点。 */
    @Transactional
    public void ensureSeaFleets(Long worldId) {
        String mask = terrain.ensure();
        long seaBanditsCount = bandits.findByWorldId(worldId).stream()
                .filter(b -> WorldTerrainService.sea(mask, b.getX(), b.getY())).count();
        if (seaBanditsCount > 0) return;
        Set<String> used = terrain.occupiedCoordinates(worldId);
        reservePlayerCities(used);
        seedSeaBlocks(worldId, mask, used, 0);
        seedSeaBlocks(worldId, mask, used, WorldConfig.MAX_NPC_LEVEL);
        int seaIndex = 0;
        for (Bandit npc : bandits.findByWorldId(worldId).stream()
                .filter(b -> WorldTerrainService.sea(mask, b.getX(), b.getY()))
                .sorted(Comparator.comparing(Bandit::getId)).toList()) {
            int level = 1 + seaIndex++ % WorldConfig.MAX_NPC_LEVEL;
            npc.setLevel(level);
            npc.setName("日寇海域守军 Lv." + level);
            if (!Boolean.TRUE.equals(npc.getDefeated()))
                npc.setArmy(JsonUtil.toJson(WorldConfig.seaNpcGarrison(level)));
            npc.setCommanderName("山本五十六");
            bandits.save(npc);
        }
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

    /** 沿用每块最多一处 NPC、两处资源的总供给预算，实际位置按既有密度均衡分配。 */
    private void seedLandBlocks(Long worldId, String mask, Set<String> used) {
        List<int[]> budgets = new ArrayList<>();
        for (int gy = 0; gy < WorldConfig.SIZE; gy += BlockSpawnPlanner.BLOCK_SIZE)
            for (int gx = 0; gx < WorldConfig.SIZE; gx += BlockSpawnPlanner.BLOCK_SIZE) {
                int slots = Math.min(3, cells(gx, gy, BlockSpawnPlanner.BLOCK_SIZE, mask, used, false, gy * 397L + gx).size());
                if (slots > 0) budgets.add(new int[]{gx, gy, slots});
            }
        var worldNpcs = npcs.findByWorldId(worldId);
        var worldBandits = bandits.findByWorldId(worldId);
        Set<String> npcUsed = npcSpacing(used, worldNpcs, worldBandits);
        var npcPlanner = new BlockSpawnPlanner(mask, WorldConfig.SIZE, false, npcUsed,
                BlockSpawnPlanner.npcPositions(worldNpcs, worldBandits));
        var wildPlanner = new BlockSpawnPlanner(mask, WorldConfig.SIZE, false, used,
                BlockSpawnPlanner.wildPositions(wilds.findByWorldId(worldId)));
        for (int[] budget : budgets) {
            int[] npc = npcPlanner.pick(npcUsed, null, null);
            if (npc != null) {
                used.add(npc[0] + "," + npc[1]);
                seedLandNpc(worldId, npc[0], npc[1], budget[0] + budget[1]);
                WorldTerrainService.reserveFootprint(npcUsed, npc[0] - 1, npc[1] - 1, 3);
            }
            for (int n = 1; n < budget[2]; n++) {
                int[] resource = wildPlanner.pick(used, null, null);
                if (resource == null) break;
                npcUsed.add(resource[0] + "," + resource[1]);
                seedWild(worldId, resource[0], resource[1], RESOURCES.get(Math.floorMod(
                        budget[0] / BlockSpawnPlanner.BLOCK_SIZE + budget[1] / BlockSpawnPlanner.BLOCK_SIZE + n, 4)),
                        budget[0] + budget[1] + n);
            }
        }
    }

    /** 保留每轮按可用海域分块计算的舰队预算，并按海域面积与既有舰队密度布点。 */
    private void seedSeaBlocks(Long worldId, String mask, Set<String> used, int levelOffset) {
        int budget = 0;
        for (int gy = 0; gy < WorldConfig.SIZE; gy += BlockSpawnPlanner.BLOCK_SIZE)
            for (int gx = 0; gx < WorldConfig.SIZE; gx += BlockSpawnPlanner.BLOCK_SIZE)
                if (!cells(gx, gy, BlockSpawnPlanner.BLOCK_SIZE, mask, used, true, gy * 811L + gx).isEmpty()) budget++;
        var worldNpcs = npcs.findByWorldId(worldId);
        var worldBandits = bandits.findByWorldId(worldId);
        Set<String> npcUsed = npcSpacing(used, worldNpcs, worldBandits);
        var planner = new BlockSpawnPlanner(mask, WorldConfig.SIZE, true, npcUsed,
                BlockSpawnPlanner.npcPositions(worldNpcs, worldBandits));
        for (int number = 1; number <= budget; number++) {
            int[] destination = planner.pick(npcUsed, null, null);
            if (destination == null) break;
            used.add(destination[0] + "," + destination[1]);
            seedSeaNpc(worldId, destination[0], destination[1], number + levelOffset);
            WorldTerrainService.reserveFootprint(npcUsed, destination[0] - 1, destination[1] - 1, 3);
        }
    }

    /** NPC 布点单独保留外围间隔，资源野地仍只避让实际占地。 */
    private Set<String> npcSpacing(Set<String> used, List<NpcCity> worldNpcs,
                                   List<Bandit> worldBandits) {
        Set<String> npcUsed = new HashSet<>(used);
        worldNpcs.forEach(n -> WorldTerrainService.reserveFootprint(npcUsed, n.getX() - 1, n.getY() - 1, 3));
        worldBandits.forEach(n -> WorldTerrainService.reserveFootprint(npcUsed, n.getX() - 1, n.getY() - 1, 3));
        return npcUsed;
    }

    /** 保留近城供给半径和数量下限，在各自允许范围内优先补到密度较低的分块。 */
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
        var resourcePositions = BlockSpawnPlanner.wildPositions(wilds.findByWorldId(worldId));
        var innerPlanner = new BlockSpawnPlanner(mask, WorldConfig.SIZE, false, used, resourcePositions, inner);
        int added = 0;
        for (int n = innerWilds; n < 4; n++) {
            int[] destination = innerPlanner.pick(used, null, null);
            if (destination == null) break;
            int cx = destination[0], cy = destination[1];
            seedWild(worldId, cx, cy, RESOURCES.get(n % 4), x + y + n);
            resourcePositions.add(destination);
            added++;
        }
        // 外圈密度包含刚补足的内圈资源，避免两段补给使用过期的数量快照。
        var nearbyPlanner = new BlockSpawnPlanner(mask, WorldConfig.SIZE, false, used, resourcePositions, nearby);
        for (int n = existingWilds + added; n < 8; n++) {
            int[] destination = nearbyPlanner.pick(used, null, null);
            if (destination == null) break;
            int cx = destination[0], cy = destination[1];
            seedWild(worldId, cx, cy, RESOURCES.get(n % 4), x + y + n);
        }
        var worldNpcs = npcs.findByWorldId(worldId);
        var worldBandits = bandits.findByWorldId(worldId);
        Set<String> npcUsed = npcSpacing(used, worldNpcs, worldBandits);
        var npcPlanner = new BlockSpawnPlanner(mask, WorldConfig.SIZE, false, npcUsed,
                BlockSpawnPlanner.npcPositions(worldNpcs, worldBandits), nearby);
        for (int n = existingNpcs; n < 2; n++) {
            int[] destination = npcPlanner.pick(npcUsed, null, null);
            if (destination == null) break;
            int cx = destination[0], cy = destination[1];
            used.add(cx + "," + cy);
            seedLandNpc(worldId, cx, cy, x + y + n);
            WorldTerrainService.reserveFootprint(npcUsed, cx - 1, cy - 1, 3);
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
        npc.setName(WorldConfig.landBanditName(level));
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
