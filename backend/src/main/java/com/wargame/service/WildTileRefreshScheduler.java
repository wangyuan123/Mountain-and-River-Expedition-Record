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
import java.util.concurrent.ThreadLocalRandom;

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

    /** 迁移耗尽野地和已击败据点；不改变目标等级及现有数量。 */
    @Transactional
    public void refreshDepletedWilds() {
        for (var candidate : worlds.findAll()) {
            var world = worlds.lockById(candidate.getId()).orElseThrow();
            String mask = terrain.current();
            if (mask == null || mask.isEmpty()) continue;
            // 自动布点限于开放战区，不能把展示世界尺寸当作可用陆地边界。
            int size = Math.min(WorldConfig.SIZE, world.getSize() == null ? WorldConfig.SIZE : world.getSize());
            Set<String> used = occupied(world.getId());
            for (WildTile tile : wilds.findByWorldId(world.getId())) {
                if (!tile.isDormant()
                        || marches.existsByTargetIdAndTargetKindIn(String.valueOf(tile.getId()), List.of("wild", "wild_gather"))) continue;
                boolean sea = WorldTerrainService.sea(mask, tile.getX(), tile.getY());
                int[] destination = randomVacant(mask, size, sea, used, tile.getX(), tile.getY(), 1);
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
            // 沿用野地刷新节奏与同地形空格抽样；未结束的行军仍指向旧坐标，不能提前迁移。
            for (var bandit : bandits.findByWorldId(world.getId())) {
                if (!Boolean.TRUE.equals(bandit.getDefeated())
                        || marches.existsByTargetIdAndTargetKindIn(String.valueOf(bandit.getId()), List.of("bandit"))) continue;
                boolean sea = WorldTerrainService.sea(mask, bandit.getX(), bandit.getY());
                int[] destination = randomVacant(mask, size, sea, used, bandit.getX(), bandit.getY(), 2);
                if (destination == null) continue;
                int level = Math.max(1, bandit.getLevel() == null ? 1 : bandit.getLevel());
                bandit.setX(destination[0]);
                bandit.setY(destination[1]);
                bandit.setArmy(JsonUtil.toJson(sea ? WorldConfig.seaNpcGarrison(level)
                        : WorldConfig.BANDIT_LEVELS.get(level - 1).army()));
                // 奖励按等级及地形在战斗时计算；清除击败状态即可恢复可领取奖励，保留名称和军官。
                bandit.setDefeated(false);
                bandits.save(bandit);
                WorldTerrainService.reserveFootprint(used, destination[0], destination[1], 2);
            }
        }
    }

    /** 占用集包含城市完整占地，防止迁移到玩家城市或其他地图目标之上。 */
    private Set<String> occupied(Long worldId) {
        Set<String> used = new HashSet<>();
        for (WildTile tile : wilds.findByWorldId(worldId))
            if (!tile.isDormant()) used.add(key(tile.getX(), tile.getY()));
        npcs.findByWorldId(worldId).forEach(n -> WorldTerrainService.reserveFootprint(used, n.getX(), n.getY(), 2));
        bandits.findByWorldId(worldId).forEach(n -> WorldTerrainService.reserveFootprint(used, n.getX(), n.getY(), 2));
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

    /** 蓄水池抽样使每个同地形空格有相同概率，且明确排除野地原坐标。 */
    private int[] randomVacant(String mask, int size, boolean sea, Set<String> used, int oldX, int oldY, int span) {
        int[] selected = null;
        int count = 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if ((x == oldX && y == oldY) || !WorldTerrainService.vacantFootprint(mask, size, sea, used, x, y, span)) continue;
            if (random.nextInt(++count) == 0) selected = new int[]{x, y};
        }
        return selected;
    }

    private String key(int x, int y) { return x + "," + y; }
}
