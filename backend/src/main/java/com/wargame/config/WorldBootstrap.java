package com.wargame.config;

import com.wargame.repository.WorldMapRepository;
import com.wargame.service.GameStateService;
import com.wargame.service.NpcCitySpawnService;
import com.wargame.service.IslandContentService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 启动时世界初始化。
 * <p>
 * 世界 (bandits / npc_cities / player_cities / wild_tiles) 是共享全局数据,
 * 首次部署生成地图；旧地图若缺少 NPC 城市则补足，不重建世界或重置玩家数据。
 */
@Component
@Profile("!test")
public class WorldBootstrap implements ApplicationRunner {

    private final WorldMapRepository worldMapRepository;
    private final GameStateService gameStateService;
    private final com.wargame.service.WorldTerrainService terrain;
    private final NpcCitySpawnService npcCitySpawnService;
    private final IslandContentService islandContentService;

    public WorldBootstrap(WorldMapRepository worldMapRepository, GameStateService gameStateService,
                          com.wargame.service.WorldTerrainService terrain, NpcCitySpawnService npcCitySpawnService,
                          IslandContentService islandContentService) {
        this.worldMapRepository = worldMapRepository;
        this.gameStateService = gameStateService; this.terrain = terrain;
        this.npcCitySpawnService = npcCitySpawnService;
        this.islandContentService = islandContentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (worldMapRepository.count() == 0) {
            gameStateService.genWorld(null);
        }
        terrain.ensure();
        worldMapRepository.findFirstByOrderByIdAsc().ifPresent(world -> {
            npcCitySpawnService.ensurePopulation(world.getId());
            islandContentService.ensure(world.getId());
        });
    }
}
