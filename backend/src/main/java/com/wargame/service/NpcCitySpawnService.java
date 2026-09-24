package com.wargame.service;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.NpcCity;
import com.wargame.repository.NpcCityRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class NpcCitySpawnService {
    private final WorldMapRepository worlds;
    private final NpcCityRepository cities;
    private final PlayerRepository players;
    private final WorldTerrainService terrain;

    public NpcCitySpawnService(WorldMapRepository worlds, NpcCityRepository cities,
                               PlayerRepository players, WorldTerrainService terrain) {
        this.worlds = worlds;
        this.cities = cities;
        this.players = players;
        this.terrain = terrain;
    }

    /** 在世界锁内选取空闲陆地；禁止在被击败城市的原坐标立即重生。 */
    @Transactional
    public NpcCity spawn(Long worldId, Integer oldX, Integer oldY) {
        worlds.lockById(worldId).orElseThrow(() -> new IllegalArgumentException("世界不存在"));
        String mask = terrain.ensure();
        Set<String> used = terrain.occupiedCoordinates(worldId);
        players.findAll().forEach(player -> {
            if (player.getCityPosX() == null || player.getCityPosY() == null) return;
            int x = WorldTerrainService.anchor(player.getCityPosX(), 2);
            int y = WorldTerrainService.anchor(player.getCityPosY(), 2);
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) used.add((x + dx) + "," + (y + dy));
        });
        int size = WorldConfig.SIZE;
        int start = ThreadLocalRandom.current().nextInt(size * size);
        for (int offset = 0; offset < size * size; offset++) {
            int position = (start + offset) % (size * size);
            int x = position % size;
            int y = position / size;
            if (WorldTerrainService.sea(mask, x, y) || used.contains(x + "," + y)
                    || (oldX != null && oldY != null && x == oldX && y == oldY)) continue;
            return spawnAt(worldId, x, y);
        }
        throw new IllegalStateException("地图没有可生成 NPC 城市的空闲陆地");
    }

    /** Caller holds the world placement lock and has reserved an empty land cell. */
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
        Map<String, Integer> supplies = new LinkedHashMap<>(tier.reward());
        supplies.remove("exp");
        city.setResources(JsonUtil.toJson(supplies));
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

    /** 新旧世界补足十二座 NPC 城市，不重建已有城市或重置玩家战果。 */
    @Transactional
    public void ensurePopulation(Long worldId) {
        int existing = cities.findByWorldId(worldId).size();
        for (int index = existing; index < 12; index++) spawn(worldId, null, null);
    }
}
