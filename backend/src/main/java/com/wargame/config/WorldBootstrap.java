package com.wargame.config;

import com.wargame.repository.WorldMapRepository;
import com.wargame.service.GameStateService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 启动时世界初始化。
 * <p>
 * 世界 (bandits / npc_cities / player_cities / wild_tiles) 是共享全局数据,
 * 只在数据库为空时生成一次, 保证全新部署下玩家不会面对一片空白的世界地图。
 */
@Component
@Profile("!test")
public class WorldBootstrap implements ApplicationRunner {

    private final WorldMapRepository worldMapRepository;
    private final GameStateService gameStateService;
    private final com.wargame.service.WorldTerrainService terrain;

    public WorldBootstrap(WorldMapRepository worldMapRepository, GameStateService gameStateService, com.wargame.service.WorldTerrainService terrain) {
        this.worldMapRepository = worldMapRepository;
        this.gameStateService = gameStateService; this.terrain = terrain;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (worldMapRepository.count() == 0) {
            gameStateService.genWorld(null);
        }
        terrain.ensure();
    }
}
