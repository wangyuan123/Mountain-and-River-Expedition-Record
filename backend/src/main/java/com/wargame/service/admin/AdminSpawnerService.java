package com.wargame.service.admin;

import com.wargame.model.entity.NpcCity;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.WorldMap;
import com.wargame.repository.NpcCityRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.service.GameStateService;
import com.wargame.service.NpcCitySpawnService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AdminSpawnerService {

    private final NpcCitySpawnService npcCitySpawnService;
    private final NpcCityRepository npcCityRepository;
    private final WorldMapRepository worldMapRepository;
    private final PlayerRepository playerRepository;
    private final PasswordEncoder passwordEncoder;
    private final GameStateService gameStateService;
    private final AdminAuditLogService auditLogService;
    private final AdminPlayerService adminPlayerService;

    public AdminSpawnerService(NpcCitySpawnService npcCitySpawnService,
                               NpcCityRepository npcCityRepository,
                               WorldMapRepository worldMapRepository,
                               PlayerRepository playerRepository,
                               PasswordEncoder passwordEncoder,
                               GameStateService gameStateService,
                               AdminAuditLogService auditLogService,
                               AdminPlayerService adminPlayerService) {
        this.npcCitySpawnService = npcCitySpawnService;
        this.npcCityRepository = npcCityRepository;
        this.worldMapRepository = worldMapRepository;
        this.playerRepository = playerRepository;
        this.passwordEncoder = passwordEncoder;
        this.gameStateService = gameStateService;
        this.auditLogService = auditLogService;
        this.adminPlayerService = adminPlayerService;
    }

    @Transactional
    public List<Map<String, Object>> spawnNpcCities(int count, Integer minLevel, Integer maxLevel, String adminUsername, HttpServletRequest req) {
        if (count <= 0 || count > 50) {
            throw new IllegalArgumentException("单次生成 NPC 数量需在 1 ~ 50 之间");
        }
        WorldMap world = worldMapRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("世界地图未初始化"));

        List<Map<String, Object>> results = new ArrayList<>();
        int minL = (minLevel != null && minLevel >= 1) ? minLevel : 1;
        int maxL = (maxLevel != null && maxLevel >= minL) ? maxLevel : 10;

        for (int i = 0; i < count; i++) {
            NpcCity city = npcCitySpawnService.spawn(world.getId(), null, null);
            if (minLevel != null || maxLevel != null) {
                int level = ThreadLocalRandom.current().nextInt(minL, maxL + 1);
                city.setLevel(level);
                npcCityRepository.save(city);
            }
            results.add(Map.of(
                    "id", city.getId(),
                    "name", city.getName(),
                    "level", city.getLevel(),
                    "x", city.getX(),
                    "y", city.getY()
            ));
        }

        auditLogService.record(adminUsername, "SPAWN_NPC", "WORLD", String.valueOf(world.getId()),
                String.format("批量生成 %d 个 NPC 城市，等级区间 [%d, %d]", count, minL, maxL), req);
        return results;
    }

    @Transactional
    public List<Map<String, Object>> spawnMockBots(int count, String namePrefix, Integer initialLevel, String adminUsername, HttpServletRequest req) {
        if (count <= 0 || count > 30) {
            throw new IllegalArgumentException("单次生成模拟玩家数量需在 1 ~ 30 之间");
        }
        String prefix = (namePrefix != null && !namePrefix.isBlank()) ? namePrefix.trim() : "Bot";
        List<Map<String, Object>> results = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String suffix = String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
            String username = (prefix + "_" + suffix).toLowerCase();
            int attempt = 0;
            while (playerRepository.existsByUsername(username) && attempt < 10) {
                suffix = String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
                username = (prefix + "_" + suffix).toLowerCase();
                attempt++;
            }

            Player player = new Player();
            player.setUsername(username);
            player.setDisplayName(prefix + "指挥官" + suffix);
            player.setPasswordHash(passwordEncoder.encode("bot123456"));
            player.setFaction(ThreadLocalRandom.current().nextBoolean() ? "allies" : "axis");
            player.setCityName(prefix + "要塞" + suffix);
            player = playerRepository.save(player);

            gameStateService.initializeNewPlayer(player.getId());

            int level = (initialLevel != null && initialLevel > 1) ? initialLevel : 1;
            if (level > 1) {
                player.setLevel(level);
                player.setMilitaryRank(Math.min(10, Math.max(1, level / 2)));
                player.setPrestige(level * 500);
                playerRepository.save(player);

                adminPlayerService.updatePlayerResources(player.getId(), Map.of(
                        "gold", level * 5000,
                        "food", level * 20000,
                        "steel", level * 20000,
                        "oil", level * 10000,
                        "rare", level * 5000
                ), "system", null);

                adminPlayerService.updatePlayerArmy(player.getId(), "infantry", level * 100, "system", null);
                adminPlayerService.updatePlayerArmy(player.getId(), "cavalry", level * 50, "system", null);
                adminPlayerService.updatePlayerArmy(player.getId(), "artillery", level * 20, "system", null);
            }

            Map<String, Object> botInfo = new LinkedHashMap<>();
            botInfo.put("id", player.getId());
            botInfo.put("username", player.getUsername());
            botInfo.put("displayName", player.getDisplayName());
            botInfo.put("cityName", player.getCityName());
            botInfo.put("level", player.getLevel());
            botInfo.put("posX", player.getCityPosX() != null ? player.getCityPosX() : 0);
            botInfo.put("posY", player.getCityPosY() != null ? player.getCityPosY() : 0);
            results.add(botInfo);
        }

        auditLogService.record(adminUsername, "SPAWN_BOTS", "PLAYER", "BATCH",
                String.format("批量生成 %d 个模拟玩家 (前缀:%s, 初始等级:%d)", count, prefix, initialLevel != null ? initialLevel : 1), req);
        return results;
    }
}
