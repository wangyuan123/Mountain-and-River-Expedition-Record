package com.wargame.service;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.WildTile;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 耗尽且废弃的野地、已击败据点先从地图消失，在北京时间固定刷新点迁移至空闲位置。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class WildTileRefreshScheduler {
    private final WorldMapRepository worlds;
    private final WildTileRepository wilds;
    private final PlayerCityRepository cities;
    private final NpcCityRepository npcs;
    private final BanditRepository bandits;
    private final PlayerRepository players;
    private final MarchRepository marches;
    private final WorldTerrainService terrain;
    private final LandBanditPopulationService landBandits;

    /** 北京时间每天 0、3、6、9、12、15、18、21 点刷新；24 点即次日 0 点。 */
    @Scheduled(cron = "0 0 0,3,6,9,12,15,18,21 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void refreshWorldTargets() {
        refreshDepletedWilds();
        // 先迁移已击败目标，再检查等级缺额，避免把等待刷新的目标当成缺失重复补点。
        for (var world : worlds.findAll()) landBandits.ensure(world.getId());
    }

    /** 迁移耗尽野地和已击败据点到同类密度较低的分块；保留等级和现有数量。 */
    @Transactional
    public void refreshDepletedWilds() {
        for (var candidate : worlds.findAll()) {
            var world = worlds.lockById(candidate.getId()).orElseThrow();
            String mask = terrain.current();
            if (mask == null || mask.isEmpty()) continue;
            // 四大战场都参与刷新，不能把外围战场的目标强制迁回西北。
            int size = Math.min(WorldConfig.WORLD_SIZE, world.getSize() == null ? WorldConfig.WORLD_SIZE : world.getSize());
            Set<String> used = occupied(world.getId());
            var worldWilds = wilds.findByWorldId(world.getId());
            var worldBandits = bandits.findByWorldId(world.getId());
            var wildPositions = BlockSpawnPlanner.wildPositions(worldWilds);
            var landWilds = new BlockSpawnPlanner(mask, size, false, used, wildPositions);
            var seaWilds = new BlockSpawnPlanner(mask, size, true, used, wildPositions);
            for (WildTile tile : worldWilds) {
                if (!tile.isDormant()
                        || marches.existsByTargetIdAndTargetKindIn(String.valueOf(tile.getId()), List.of("wild", "wild_gather"))) continue;
                boolean sea = WorldTerrainService.sea(mask, tile.getX(), tile.getY());
                int[] destination = (sea ? seaWilds : landWilds).pick(used, tile.getX(), tile.getY());
                if (destination == null) continue;
                tile.setX(destination[0]);
                tile.setY(destination[1]);
                tile.setMined(0);
                int level = Math.max(1, tile.getLevel() == null ? 1 : tile.getLevel());
                tile.setTotalRes(level * WorldConfig.RES_PER_WILD_LEVEL);
                tile.setGarrison(JsonUtil.toJson(WorldConfig.wildGarrison(level, sea)));
                tile.setScouted(false);
                tile.setDepletedAt(null);
                wilds.save(tile);
                used.add(key(destination[0], destination[1]));
            }
            // 野地迁移后再预留 NPC 间距；待刷新的旧坐标本轮仍保留，保护行军和占位。
            var worldNpcs = npcs.findByWorldId(world.getId());
            worldNpcs.forEach(n -> WorldTerrainService.reserveFootprint(used, n.getX() - 1, n.getY() - 1, 3));
            worldBandits.forEach(n -> WorldTerrainService.reserveFootprint(used, n.getX() - 1, n.getY() - 1, 3));
            var npcPositions = BlockSpawnPlanner.npcPositions(worldNpcs, worldBandits);
            var landNpcs = new BlockSpawnPlanner(mask, size, false, used, npcPositions);
            var seaNpcs = new BlockSpawnPlanner(mask, size, true, used, npcPositions);
            for (var bandit : worldBandits) {
                if (!Boolean.TRUE.equals(bandit.getDefeated())
                        || marches.existsByTargetIdAndTargetKindIn(String.valueOf(bandit.getId()), List.of("bandit"))) continue;
                boolean sea = WorldTerrainService.sea(mask, bandit.getX(), bandit.getY());
                int[] destination = (sea ? seaNpcs : landNpcs).pick(used, bandit.getX(), bandit.getY());
                if (destination == null) continue;
                int level = Math.max(1, bandit.getLevel() == null ? 1 : bandit.getLevel());
                bandit.setX(destination[0]);
                bandit.setY(destination[1]);
                bandit.setArmy(JsonUtil.toJson(sea ? WorldConfig.seaNpcGarrison(level, destination[0], destination[1])
                        : WorldConfig.landNpcGarrison(level, destination[0], destination[1])));
                if (!sea) bandit.setName(WorldConfig.landBanditName(level));
                // 奖励按等级及地形在战斗时计算；清除击败状态即可恢复可领取奖励，保留名称和军官。
                bandit.setDefeated(false);
                bandits.save(bandit);
                WorldTerrainService.reserveFootprint(used, destination[0] - 1, destination[1] - 1, 3);
            }
        }
    }

    /** 占用集包含城市完整占地，防止迁移到玩家城市或其他地图目标之上。 */
    private Set<String> occupied(Long worldId) {
        Set<String> used = new HashSet<>();
        for (WildTile tile : wilds.findByWorldId(worldId))
            if (!tile.isDormant()) used.add(key(tile.getX(), tile.getY()));
        npcs.findByWorldId(worldId).forEach(n -> WorldTerrainService.reserveFootprint(used, n.getX(), n.getY(), 1));
        bandits.findByWorldId(worldId).forEach(n -> WorldTerrainService.reserveFootprint(used, n.getX(), n.getY(), 1));
        for (PlayerCity city : cities.findByWorldId(worldId)) {
            int span = city.getOwnerId() != null && players.existsById(city.getOwnerId()) ? 2 : 1;
            for (int dx = 0; dx < span; dx++) for (int dy = 0; dy < span; dy++)
                used.add(key(city.getX() + dx, city.getY() + dy));
        }
        players.findAll().forEach(player -> {
            if (player.getCityPosX() != null && player.getCityPosY() != null)
                for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++)
                    used.add(key(player.getCityPosX() + dx, player.getCityPosY() + dy));
        });
        return used;
    }

    private String key(int x, int y) { return x + "," + y; }
}
