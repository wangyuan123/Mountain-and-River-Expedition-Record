package com.wargame.service;

import com.wargame.model.constants.JapaneseOfficers;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.NpcCity;
import com.wargame.repository.NpcCityRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class NpcCitySpawnService {
    private final WorldMapRepository worlds;
    private final NpcCityRepository cities;
    private final PlayerRepository players;
    private final WorldTerrainService terrain;
    private final NpcFootprintService footprints;

    public NpcCitySpawnService(WorldMapRepository worlds, NpcCityRepository cities,
                               PlayerRepository players, WorldTerrainService terrain, NpcFootprintService footprints) {
        this.worlds = worlds;
        this.cities = cities;
        this.players = players;
        this.terrain = terrain;
        this.footprints = footprints;
    }

    /** 在世界锁内选取空闲陆地；禁止在被击败城市的原坐标立即重生。 */
    @Transactional
    public NpcCity spawn(Long worldId, Integer oldX, Integer oldY) {
        worlds.lockById(worldId).orElseThrow(() -> new IllegalArgumentException("世界不存在"));
        String mask = terrain.ensure();
        Set<String> used = terrain.occupiedCoordinates(worldId);
        footprints.reserveSpacing(worldId, used);
        players.findAll().forEach(player -> {
            if (player.getCityPosX() == null || player.getCityPosY() == null) return;
            int x = WorldTerrainService.anchor(player.getCityPosX(), 2);
            int y = WorldTerrainService.anchor(player.getCityPosY(), 2);
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) used.add((x + dx) + "," + (y + dy));
        });
        int size = WorldConfig.SIZE;
        int start = ThreadLocalRandom.current().nextInt(size * size);
        for (int offset = 0; offset < size * size; offset++) {
            // 跨行列遍历，避免顺着同一行把 NPC 连续补在一起。
            int position = (int) ((start + (long) offset * (size + 1)) % (size * size));
            int x = position % size;
            int y = position / size;
            if (!WorldTerrainService.vacantFootprint(mask, size, false, used, x, y, 2)
                    || (oldX != null && oldY != null && x == oldX && y == oldY)) continue;
            return spawnAt(worldId, x, y);
        }
        throw new IllegalStateException("地图没有可生成 NPC 城市的空闲陆地");
    }

    /** 在已预留的空陆格生成 NPC 城市，并使用陆海 NPC 共用的资源奖励档位。 */
    public NpcCity spawnAt(Long worldId, int x, int y) {
        int level = ThreadLocalRandom.current().nextInt(3, 9);
        WorldConfig.BanditLevel tier = WorldConfig.BANDIT_LEVELS.get(level - 1);
        NpcCity city = new NpcCity();
        city.setWorldId(worldId);
        city.setName(WorldConfig.NPC_CITY_NAMES.get(ThreadLocalRandom.current().nextInt(WorldConfig.NPC_CITY_NAMES.size())));
        city.setLevel(level);
        city.setX(x);
        city.setY(y);
        city.setArmy(JsonUtil.toJson(tier.army()));
        city.setForts(JsonUtil.toJson(Map.of("bunker", level * 2, "antitank", level)));
        city.setResources(JsonUtil.toJson(WorldConfig.npcReward(level)));
        city.setCommanderName(JapaneseOfficers.getCommanderForLevel(level));
        city.setDefeated(false);
        city.setScoutedBy("[]");
        return cities.save(city);
    }

    /** 删除已征服的旧城并补一座新城，保留战报中的旧城名称和坐标。 */
    @Transactional
    public NpcCity replace(NpcCity defeated) {
        Long worldId = defeated.getWorldId();
        int oldX = defeated.getX();
        int oldY = defeated.getY();
        worlds.lockById(worldId).orElseThrow(() -> new IllegalArgumentException("世界不存在"));
        cities.delete(defeated);
        cities.flush();
        return spawn(worldId, oldX, oldY);
    }

    /** 新旧世界补足十二座 NPC 城市，并同步既有城市的资源奖励；不重置守军或玩家战果。 */
    @Transactional
    public void ensurePopulation(Long worldId) {
        footprints.repair(worldId);
        var existing = cities.findByWorldId(worldId);
        for (NpcCity city : existing) {
            int level = city.getLevel() == null ? 0 : city.getLevel();
            if (level < 1 || level > WorldConfig.BANDIT_LEVELS.size()) continue;
            Map<String, Integer> reward = WorldConfig.npcReward(level);
            if (!reward.equals(JsonUtil.parseIntMap(city.getResources()))) {
                city.setResources(JsonUtil.toJson(reward));
                cities.save(city);
            }
        }
        for (int index = existing.size(); index < 12; index++) spawn(worldId, null, null);
    }
}
