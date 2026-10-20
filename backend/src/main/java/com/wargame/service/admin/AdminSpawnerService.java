package com.wargame.service.admin;

import com.wargame.util.GameClock;
import com.wargame.model.constants.JapaneseOfficers;
import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.Bandit;
import com.wargame.model.entity.BattleSession;
import com.wargame.model.entity.March;
import com.wargame.model.entity.NpcCity;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.WildTile;
import com.wargame.model.entity.WorldMap;
import com.wargame.repository.BanditRepository;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.IncomingMarchRepository;
import com.wargame.repository.MarchRepository;
import com.wargame.repository.NpcCityRepository;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.WildTileRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.service.GameStateService;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
public class AdminSpawnerService {

    private final NpcCityRepository npcCityRepository;
    private final BanditRepository banditRepository;
    private final WorldMapRepository worldMapRepository;
    private final PlayerRepository playerRepository;
    private final PlayerCityRepository playerCityRepository;
    private final MarchRepository marchRepository;
    private final IncomingMarchRepository incomingMarchRepository;
    private final BattleSessionRepository battleSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final GameStateService gameStateService;
    private final AdminAuditLogService auditLogService;
    private final AdminPlayerService adminPlayerService;
    private final EntityManager em;
    private final WildTileRepository wildTileRepository;
    private final WorldTerrainService worldTerrainService;

    public AdminSpawnerService(NpcCityRepository npcCityRepository,
                               BanditRepository banditRepository,
                               WorldMapRepository worldMapRepository,
                               PlayerRepository playerRepository,
                               PlayerCityRepository playerCityRepository,
                               MarchRepository marchRepository,
                               IncomingMarchRepository incomingMarchRepository,
                               BattleSessionRepository battleSessionRepository,
                               PasswordEncoder passwordEncoder,
                               GameStateService gameStateService,
                               AdminAuditLogService auditLogService,
                               AdminPlayerService adminPlayerService,
                               EntityManager em,
                               WildTileRepository wildTileRepository,
                               WorldTerrainService worldTerrainService) {
        this.npcCityRepository = npcCityRepository;
        this.banditRepository = banditRepository;
        this.worldMapRepository = worldMapRepository;
        this.playerRepository = playerRepository;
        this.playerCityRepository = playerCityRepository;
        this.marchRepository = marchRepository;
        this.incomingMarchRepository = incomingMarchRepository;
        this.battleSessionRepository = battleSessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.gameStateService = gameStateService;
        this.auditLogService = auditLogService;
        this.adminPlayerService = adminPlayerService;
        this.em = em;
        this.wildTileRepository = wildTileRepository;
        this.worldTerrainService = worldTerrainService;
    }

    public record BattlefieldBounds(String battlefield, String name, int minX, int maxX, int minY, int maxY) {}

    public static BattlefieldBounds getBounds(String battlefield, int span) {
        String bf = battlefield != null ? battlefield.trim().toLowerCase() : "";
        if (bf.isEmpty() || "all".equals(bf)) {
            return new BattlefieldBounds("all", "全图随机", 0, WorldConfig.WORLD_SIZE - span, 0, WorldConfig.WORLD_SIZE - span);
        }
        int minX = bf.endsWith("east") ? WorldConfig.SIZE : 0;
        int minY = bf.startsWith("south") ? WorldConfig.SIZE : 0;
        int maxX = minX + WorldConfig.SIZE - span;
        int maxY = minY + WorldConfig.SIZE - span;

        String name = switch (bf) {
            case "northwest" -> "西北战场";
            case "northeast" -> "东北战场";
            case "southwest" -> "西南战场";
            case "southeast" -> "东南战场";
            default -> "全图随机";
        };
        return new BattlefieldBounds(bf, name, minX, maxX, minY, maxY);
    }

    public static String getBattlefieldName(int x, int y) {
        boolean east = x >= WorldConfig.SIZE;
        boolean south = y >= WorldConfig.SIZE;
        if (!south && !east) return "西北战场";
        if (!south && east) return "东北战场";
        if (south && !east) return "西南战场";
        return "东南战场";
    }

    public static String getBattlefieldKey(int x, int y) {
        boolean east = x >= WorldConfig.SIZE;
        boolean south = y >= WorldConfig.SIZE;
        if (!south && !east) return "northwest";
        if (!south && east) return "northeast";
        if (south && !east) return "southwest";
        return "southeast";
    }

    @Transactional
    public List<Map<String, Object>> spawnNpcCities(int count, Integer minLevel, Integer maxLevel, String adminUsername, HttpServletRequest req) {
        return spawnNpcCities(count, minLevel, maxLevel, "all", adminUsername, req);
    }

    @Transactional
    public List<Map<String, Object>> spawnNpcCities(int count, Integer minLevel, Integer maxLevel, String battlefield, String adminUsername, HttpServletRequest req) {
        if (count <= 0 || count > 50) {
            throw new IllegalArgumentException("单次生成 NPC 据点数量需在 1 ~ 50 之间");
        }
        WorldMap world = worldMapRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("世界地图未初始化"));
        Long worldId = world.getId();

        int minL = (minLevel != null && minLevel >= 1) ? minLevel : 1;
        int maxL = (maxLevel != null && maxLevel >= 1) ? maxLevel : 10;
        if (minL > maxL) {
            int tmp = minL;
            minL = maxL;
            maxL = tmp;
        }

        BattlefieldBounds bounds = getBounds(battlefield, 1);
        String mask = worldTerrainService.current();
        if (mask == null) {
            mask = WorldTerrainService.generate();
        }

        Set<String> used = new HashSet<>(worldTerrainService.occupiedCoordinates(worldId));
        int W = bounds.maxX() - bounds.minX() + 1;
        int H = bounds.maxY() - bounds.minY() + 1;
        int totalCells = W * H;
        int startOffset = ThreadLocalRandom.current().nextInt(totalCells);
        List<int[]> candidates = new ArrayList<>();

        for (int i = 0; i < totalCells && candidates.size() < count; i++) {
            int idx = (startOffset + i) % totalCells;
            int x = bounds.minX() + (idx % W);
            int y = bounds.minY() + (idx / W);
            String coordKey = x + "," + y;
            if (!used.contains(coordKey) && !WorldTerrainService.sea(mask, x, y)) {
                candidates.add(new int[]{x, y});
                used.add(coordKey);
            }
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException("所选区域（" + bounds.name() + "）陆地上已无可用空闲坐标生成据点");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (int[] coord : candidates) {
            int level = ThreadLocalRandom.current().nextInt(minL, maxL + 1);
            Bandit npc = new Bandit();
            npc.setWorldId(worldId);
            npc.setX(coord[0]);
            npc.setY(coord[1]);
            npc.setLevel(level);
            npc.setName(WorldConfig.landBanditName(level));
            npc.setArmy(JsonUtil.toJson(WorldConfig.landNpcGarrison(level, coord[0], coord[1])));
            npc.setCommanderName(JapaneseOfficers.getCommanderForLevel(level));
            npc.setDefeated(false);
            banditRepository.save(npc);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", npc.getId());
            item.put("name", npc.getName());
            item.put("level", npc.getLevel());
            item.put("x", npc.getX());
            item.put("y", npc.getY());
            item.put("commanderName", npc.getCommanderName());
            item.put("battlefield", getBattlefieldName(coord[0], coord[1]));
            results.add(item);
        }

        auditLogService.record(adminUsername, "SPAWN_NPC", "WORLD", String.valueOf(worldId),
                String.format("在【%s】批量生成 %d 个 NPC 据点，等级区间 [%d, %d]", bounds.name(), results.size(), minL, maxL), req);
        return results;
    }

    @Transactional
    public List<Map<String, Object>> spawnMockBots(int count, String namePrefix, Integer initialLevel, String adminUsername, HttpServletRequest req) {
        return spawnMockBots(count, namePrefix, initialLevel, "all", adminUsername, req);
    }

    @Transactional
    public List<Map<String, Object>> spawnMockBots(int count, String namePrefix, Integer initialLevel, String battlefield, String adminUsername, HttpServletRequest req) {
        if (count <= 0 || count > 30) {
            throw new IllegalArgumentException("单次生成模拟玩家数量需在 1 ~ 30 之间");
        }
        WorldMap worldMap = worldMapRepository.findFirstByOrderByIdAsc().orElse(null);
        Long worldId = worldMap != null ? worldMap.getId() : 1L;

        String prefix = (namePrefix != null && !namePrefix.isBlank() && !"Bot".equalsIgnoreCase(namePrefix.trim()))
                ? namePrefix.trim()
                : "远征统帅";
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> batchNames = new HashSet<>();
        String prefixTag = prefix.endsWith("_") ? prefix : prefix + "_";

        BattlefieldBounds bounds = getBounds(battlefield, 2);
        String mask = worldTerrainService.current();
        if (mask == null) {
            mask = WorldTerrainService.generate();
        }

        Set<String> used = new HashSet<>(worldTerrainService.occupiedCoordinates(worldId));
        int W = bounds.maxX() - bounds.minX() + 1;
        int H = bounds.maxY() - bounds.minY() + 1;
        int totalCells = W * H;

        for (int i = 0; i < count; i++) {
            int startOffset = ThreadLocalRandom.current().nextInt(totalCells);
            int[] cityCoord = null;
            for (int j = 0; j < totalCells; j++) {
                int idx = (startOffset + j) % totalCells;
                int x = bounds.minX() + (idx % W);
                int y = bounds.minY() + (idx / W);
                if (WorldTerrainService.vacantFootprint(mask, WorldConfig.WORLD_SIZE, false, used, x, y, 2)) {
                    cityCoord = new int[]{x, y};
                    WorldTerrainService.reserveFootprint(used, x, y, 2);
                    break;
                }
            }

            if (cityCoord == null) {
                if (results.isEmpty()) {
                    throw new IllegalStateException("所选区域（" + bounds.name() + "）已无足够空地容纳 2×2 城池");
                }
                break;
            }

            String displayName = playerRepository.generateDefaultCommanderName(prefix, batchNames);
            String username = displayName.toLowerCase();
            batchNames.add(displayName.toLowerCase());
            batchNames.add(username);
            String suffix = displayName.substring(prefixTag.length());

            Player player = new Player();
            player.setUsername(username);
            player.setDisplayName(displayName);
            player.setIsBot(true);
            player.setPasswordHash(passwordEncoder.encode("bot123456"));
            player.setFaction(ThreadLocalRandom.current().nextBoolean() ? "allies" : "axis");
            String fortressBase = prefix.replaceAll("[_统帅]+$", "");
            if (fortressBase.isBlank()) fortressBase = prefix;
            player.setCityName(fortressBase + "要塞" + suffix);
            player = playerRepository.save(player);

            gameStateService.initializeNewPlayer(player.getId());

            // 重新校准并锁定城池到指定战场的真实空闲坐标
            player.setPosX(cityCoord[0]);
            player.setPosY(cityCoord[1]);
            player.setCityPosX(cityCoord[0]);
            player.setCityPosY(cityCoord[1]);

            int level = (initialLevel != null && initialLevel > 1) ? initialLevel : 1;
            if (level > 1) {
                player.setLevel(level);
                player.setMilitaryRank(Math.min(10, Math.max(1, level / 2)));
                player.setPrestige(level * 500);

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
            playerRepository.save(player);

            PlayerCity city = playerCityRepository.findByOwnerIdAndCitySlot(player.getId(), 0).orElse(null);
            if (city != null) {
                city.setX(cityCoord[0]);
                city.setY(cityCoord[1]);
                playerCityRepository.save(city);
            }

            Map<String, Object> botInfo = new LinkedHashMap<>();
            botInfo.put("id", player.getId());
            botInfo.put("username", player.getUsername());
            botInfo.put("displayName", player.getDisplayName());
            botInfo.put("cityName", player.getCityName());
            botInfo.put("level", player.getLevel());
            botInfo.put("posX", cityCoord[0]);
            botInfo.put("posY", cityCoord[1]);
            botInfo.put("battlefield", getBattlefieldName(cityCoord[0], cityCoord[1]));
            results.add(botInfo);
        }

        auditLogService.record(adminUsername, "SPAWN_BOTS", "PLAYER", "BATCH",
                String.format("在【%s】批量生成 %d 个模拟玩家 (前缀:%s, 初始等级:%d)", bounds.name(), results.size(), prefix, initialLevel != null ? initialLevel : 1), req);
        return results;
    }

    /** 供管理员查询并清理无行军或战斗引用的 NPC 据点（包含 Bandit 与旧 NpcCity）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDeletableNpcCities() {
        WorldMap world = worldMapRepository.findFirstByOrderByIdAsc().orElse(null);
        Long worldId = world != null ? world.getId() : 1L;

        List<Bandit> bandits = banditRepository.findByWorldId(worldId);
        List<NpcCity> npcs = npcCityRepository.findAll();
        List<March> marches = marchRepository.findAll();
        List<BattleSession> battles = battleSessionRepository.findAll();

        Set<String> busyNpcIds = new HashSet<>();
        Set<String> busyCoords = new HashSet<>();

        for (March m : marches) {
            if ("npc".equalsIgnoreCase(m.getTargetKind()) || "bandit".equalsIgnoreCase(m.getTargetKind())) {
                if (m.getTargetId() != null) busyNpcIds.add(m.getTargetId());
                if (m.getTargetX() != null && m.getTargetY() != null) {
                    busyCoords.add(m.getTargetX() + "," + m.getTargetY());
                }
            }
        }

        for (BattleSession b : battles) {
            if (("npc".equalsIgnoreCase(b.getTargetKind()) || "bandit".equalsIgnoreCase(b.getTargetKind())) && b.getTargetId() != null) {
                busyNpcIds.add(b.getTargetId());
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();

        // 1. 查询当前系统的活跃据点 (Bandit)
        for (Bandit b : bandits) {
            if (Boolean.TRUE.equals(b.getDefeated())) {
                continue;
            }
            if (busyNpcIds.contains(String.valueOf(b.getId())) || busyCoords.contains(b.getX() + "," + b.getY())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", b.getId());
            item.put("name", b.getName());
            item.put("level", b.getLevel());
            item.put("x", b.getX());
            item.put("y", b.getY());
            item.put("commanderName", b.getCommanderName() != null ? b.getCommanderName() : "无");
            item.put("battlefield", getBattlefieldName(b.getX(), b.getY()));
            item.put("battlefieldKey", getBattlefieldKey(b.getX(), b.getY()));
            item.put("status", "SAFE_TO_DELETE");
            results.add(item);
        }

        // 2. 兼容历史遗留的 NpcCity
        for (NpcCity nc : npcs) {
            if (Boolean.TRUE.equals(nc.getDefeated())) {
                continue;
            }
            if (busyNpcIds.contains(String.valueOf(nc.getId())) || busyCoords.contains(nc.getX() + "," + nc.getY())) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", nc.getId());
            item.put("name", nc.getName());
            item.put("level", nc.getLevel());
            item.put("x", nc.getX());
            item.put("y", nc.getY());
            item.put("commanderName", nc.getCommanderName() != null ? nc.getCommanderName() : "无");
            item.put("battlefield", getBattlefieldName(nc.getX(), nc.getY()));
            item.put("battlefieldKey", getBattlefieldKey(nc.getX(), nc.getY()));
            item.put("status", "SAFE_TO_DELETE");
            results.add(item);
        }
        return results;
    }

    /** 清理 NPC 据点（支持同时清理 Bandit 与旧 NpcCity）。 */
    @Transactional
    public Map<String, Object> deleteNpcCities(List<Long> ids, String adminUsername, HttpServletRequest req) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的 NPC 据点");
        }
        List<March> marches = marchRepository.findAll();
        List<BattleSession> battles = battleSessionRepository.findAll();
        Set<String> busyNpcIds = new HashSet<>();
        Set<String> busyCoords = new HashSet<>();
        for (March m : marches) {
            if ("npc".equalsIgnoreCase(m.getTargetKind()) || "bandit".equalsIgnoreCase(m.getTargetKind())) {
                if (m.getTargetId() != null) busyNpcIds.add(m.getTargetId());
                if (m.getTargetX() != null && m.getTargetY() != null) {
                    busyCoords.add(m.getTargetX() + "," + m.getTargetY());
                }
            }
        }
        for (BattleSession b : battles) {
            if (("npc".equalsIgnoreCase(b.getTargetKind()) || "bandit".equalsIgnoreCase(b.getTargetKind())) && b.getTargetId() != null) {
                busyNpcIds.add(b.getTargetId());
            }
        }

        List<Long> deletedIds = new ArrayList<>();
        List<Long> skippedIds = new ArrayList<>();

        for (Long id : ids) {
            Optional<Bandit> optBandit = banditRepository.findById(id);
            if (optBandit.isPresent()) {
                Bandit b = optBandit.get();
                if (Boolean.TRUE.equals(b.getDefeated())
                        || busyNpcIds.contains(String.valueOf(b.getId()))
                        || busyCoords.contains(b.getX() + "," + b.getY())) {
                    skippedIds.add(id);
                    continue;
                }
                banditRepository.delete(b);
                deletedIds.add(id);
                continue;
            }

            Optional<NpcCity> opt = npcCityRepository.findById(id);
            if (opt.isEmpty()) {
                skippedIds.add(id);
                continue;
            }
            NpcCity nc = opt.get();
            if (Boolean.TRUE.equals(nc.getDefeated())
                    || busyNpcIds.contains(String.valueOf(nc.getId()))
                    || busyCoords.contains(nc.getX() + "," + nc.getY())) {
                skippedIds.add(id);
                continue;
            }
            npcCityRepository.delete(nc);
            deletedIds.add(id);
        }
        banditRepository.flush();
        npcCityRepository.flush();

        if (!deletedIds.isEmpty()) {
            auditLogService.record(adminUsername, "DELETE_NPC", "NPC_CITY", "BATCH",
                    String.format("批量删除 %d 个 NPC 据点 (ID: %s)", deletedIds.size(), deletedIds), req);
        }

        return Map.of(
                "success", true,
                "deletedCount", deletedIds.size(),
                "deletedIds", deletedIds,
                "skippedCount", skippedIds.size(),
                "skippedIds", skippedIds
        );
    }

    /**
     * 查询未被占领且无任何玩家征服、掠夺、侦查、宣战或战斗中的模拟玩家 (Bot)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDeletableBots() {
        long now = GameClock.now();
        List<Player> allPlayers = playerRepository.findAll();
        List<Player> bots = allPlayers.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsBot())
                        || (p.getUsername() != null && (p.getUsername().startsWith("远征军_") || p.getUsername().startsWith("bot_") || p.getUsername().startsWith("testbot_"))))
                .toList();

        List<March> allMarches = marchRepository.findAll();
        List<BattleSession> allBattles = battleSessionRepository.findAll();

        List<Map<String, Object>> results = new ArrayList<>();

        for (Player bot : bots) {
            Long botId = bot.getId();

            // 1. 检查宣战状态 (bot 主动宣战别人，或宣战期未过)
            if (bot.getWarAgainstId() != null || (bot.getWarEndAt() != null && bot.getWarEndAt() > now)) {
                continue;
            }
            // 检查别人是否向 bot 宣战中
            boolean warTargeted = allPlayers.stream().anyMatch(other ->
                    botId.equals(other.getWarAgainstId()) && (other.getWarEndAt() != null && other.getWarEndAt() > now));
            if (warTargeted) {
                continue;
            }

            // 2. 检查城市是否被真实玩家占领
            List<PlayerCity> cities = playerCityRepository.findByOwnerId(botId);
            if (cities.isEmpty()) {
                continue;
            }

            Set<String> cityIdStrs = cities.stream().map(c -> String.valueOf(c.getId())).collect(Collectors.toSet());
            String botIdStr = String.valueOf(botId);

            // 3. 检查是否有玩家发起征服/掠夺/侦查/援防等行军，或 bot 自身有行军在外
            boolean hasMarch = allMarches.stream().anyMatch(m -> {
                if (botId.equals(m.getPlayerId())) return true;
                if ("player".equalsIgnoreCase(m.getTargetKind())) {
                    if (botIdStr.equals(m.getTargetId()) || cityIdStrs.contains(m.getTargetId())) return true;
                    if (bot.getCityPosX() != null && bot.getCityPosY() != null
                            && bot.getCityPosX().equals(m.getTargetX()) && bot.getCityPosY().equals(m.getTargetY())) {
                        return true;
                    }
                }
                return false;
            });
            if (hasMarch) {
                continue;
            }

            // 检查 IncomingMarch
            if (!incomingMarchRepository.findByTargetPlayerId(botId).isEmpty()) {
                continue;
            }

            // 4. 检查是否有交战会话 (BattleSession)
            boolean inBattle = allBattles.stream().anyMatch(b -> {
                if (botId.equals(b.getPlayerId())) return true;
                if ("player".equalsIgnoreCase(b.getTargetKind())) {
                    if (botIdStr.equals(b.getTargetId()) || cityIdStrs.contains(b.getTargetId())) return true;
                }
                return false;
            });
            if (inBattle) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", bot.getId());
            item.put("username", bot.getUsername());
            item.put("displayName", bot.getDisplayName());
            item.put("cityName", bot.getCityName());
            item.put("level", bot.getLevel());
            item.put("faction", bot.getFaction());
            int px = bot.getCityPosX() != null ? bot.getCityPosX() : 0;
            int py = bot.getCityPosY() != null ? bot.getCityPosY() : 0;
            item.put("posX", px);
            item.put("posY", py);
            item.put("militaryRank", bot.getMilitaryRank());
            item.put("prestige", bot.getPrestige());
            item.put("battlefield", getBattlefieldName(px, py));
            item.put("battlefieldKey", getBattlefieldKey(px, py));
            item.put("status", "SAFE_TO_DELETE");
            results.add(item);
        }

        return results;
    }

    /**
     * 批量删除模拟玩家 (Bot)（执行前二次安全校验，并级联清除玩家相关数据）
     */
    @Transactional
    public Map<String, Object> deleteBots(List<Long> ids, String adminUsername, HttpServletRequest req) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的模拟玩家");
        }
        long now = GameClock.now();
        List<Player> allPlayers = playerRepository.findAll();
        List<March> allMarches = marchRepository.findAll();
        List<BattleSession> allBattles = battleSessionRepository.findAll();

        List<Long> deletedIds = new ArrayList<>();
        List<Long> skippedIds = new ArrayList<>();

        for (Long id : ids) {
            Optional<Player> opt = playerRepository.findById(id);
            if (opt.isEmpty()) {
                skippedIds.add(id);
                continue;
            }
            Player bot = opt.get();
            Long botId = bot.getId();

            // 安全复核
            if (bot.getWarAgainstId() != null || (bot.getWarEndAt() != null && bot.getWarEndAt() > now)) {
                skippedIds.add(id);
                continue;
            }
            boolean warTargeted = allPlayers.stream().anyMatch(other ->
                    botId.equals(other.getWarAgainstId()) && (other.getWarEndAt() != null && other.getWarEndAt() > now));
            if (warTargeted) {
                skippedIds.add(id);
                continue;
            }
            List<PlayerCity> cities = playerCityRepository.findByOwnerId(botId);
            Set<String> cityIdStrs = cities.stream().map(c -> String.valueOf(c.getId())).collect(Collectors.toSet());
            String botIdStr = String.valueOf(botId);

            boolean hasMarch = allMarches.stream().anyMatch(m -> {
                if (botId.equals(m.getPlayerId())) return true;
                if ("player".equalsIgnoreCase(m.getTargetKind())) {
                    if (botIdStr.equals(m.getTargetId()) || cityIdStrs.contains(m.getTargetId())) return true;
                    if (bot.getCityPosX() != null && bot.getCityPosY() != null
                            && bot.getCityPosX().equals(m.getTargetX()) && bot.getCityPosY().equals(m.getTargetY())) {
                        return true;
                    }
                }
                return false;
            });
            if (hasMarch || !incomingMarchRepository.findByTargetPlayerId(botId).isEmpty()) {
                skippedIds.add(id);
                continue;
            }

            boolean inBattle = allBattles.stream().anyMatch(b -> {
                if (botId.equals(b.getPlayerId())) return true;
                if ("player".equalsIgnoreCase(b.getTargetKind())) {
                    if (botIdStr.equals(b.getTargetId()) || cityIdStrs.contains(b.getTargetId())) return true;
                }
                return false;
            });
            if (inBattle) {
                skippedIds.add(id);
                continue;
            }

            // 执行全面级联清除
            for (String entity : List.of("GuildApplication", "GuildMember", "UnitLoadout", "UnitEquipmentOrder", "OfficerEquipment",
                    "ArmyProductionQueue", "TechResearchQueue", "PrisonerUnit", "PrisonerOperation", "BattleSettlement",
                    "WoundedUnit", "Building", "ArmyUnit", "Fortification", "Technology", "Officer", "Construction",
                    "Resources", "CityState", "Academy", "PlayerItem", "ScoutReport", "PlayerQuest", "PlayerGuide", "March", "ChatMessage")) {
                em.createQuery("delete from " + entity + " e where e.playerId = :id").setParameter("id", botId).executeUpdate();
            }
            em.createQuery("delete from IncomingMarch m where m.targetPlayerId = :id").setParameter("id", botId).executeUpdate();
            em.createQuery("delete from Mail m where m.toPlayerId = :id").setParameter("id", botId).executeUpdate();
            em.createQuery("update Mail m set m.fromName = '已删除' where m.fromPlayerId = :id").setParameter("id", botId).executeUpdate();
            em.createQuery("update WildTile w set w.occupied = false, w.occupiedBy = null, w.garrison = '{}', "
                    + "w.gathering = false, w.gatherStartAt = 0, w.gatherEndAt = 0, w.gatherLoad = 0, w.gatherRes = null, w.version = w.version + 1 "
                    + "where w.occupiedBy = :id").setParameter("id", botId).executeUpdate();
            em.createQuery("update Player p set p.warAgainstId = null, p.warAt = 0, p.warEndAt = 0, p.version = p.version + 1 "
                    + "where p.warAgainstId = :id").setParameter("id", botId).executeUpdate();
            em.createQuery("delete from PlayerCity c where c.ownerId = :id").setParameter("id", botId).executeUpdate();

            playerRepository.delete(bot);
            deletedIds.add(botId);
        }

        em.flush();

        if (!deletedIds.isEmpty()) {
            auditLogService.record(adminUsername, "DELETE_BOTS", "PLAYER", "BATCH",
                    String.format("批量删除 %d 个模拟玩家 (ID: %s)", deletedIds.size(), deletedIds), req);
        }

        return Map.of(
                "success", true,
                "deletedCount", deletedIds.size(),
                "deletedIds", deletedIds,
                "skippedCount", skippedIds.size(),
                "skippedIds", skippedIds
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getWildStats() {
        WorldMap worldMap = worldMapRepository.findFirstByOrderByIdAsc().orElse(null);
        Long worldId = worldMap != null ? worldMap.getId() : 1L;
        long totalCount = wildTileRepository.countByWorldId(worldId);
        long occupiedCount = wildTileRepository.countByWorldIdAndOccupiedTrue(worldId);
        long unoccupiedCount = wildTileRepository.countUnoccupiedByWorldId(worldId);

        List<Object[]> typeCounts = wildTileRepository.countGroupByTypeByWorldId(worldId);
        Map<String, Long> byType = new LinkedHashMap<>();
        long resourceCount = 0;
        Set<String> resTypes = Set.of("grainfield", "ironworks", "oil", "rarefactory");

        for (Object[] row : typeCounts) {
            String t = (String) row[0];
            long cnt = ((Number) row[1]).longValue();
            byType.put(t, cnt);
            if (resTypes.contains(t)) {
                resourceCount += cnt;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", totalCount);
        result.put("occupiedCount", occupiedCount);
        result.put("unoccupiedCount", unoccupiedCount);
        result.put("resourceCount", resourceCount);
        result.put("byType", byType);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDeletableWildTiles() {
        WorldMap worldMap = worldMapRepository.findFirstByOrderByIdAsc().orElse(null);
        Long worldId = worldMap != null ? worldMap.getId() : 1L;
        List<WildTile> allWilds = wildTileRepository.findByWorldId(worldId);
        List<March> marches = marchRepository.findAll();
        List<BattleSession> battles = battleSessionRepository.findAll();

        Set<String> busyIds = new HashSet<>();
        Set<String> busyCoords = new HashSet<>();

        for (March m : marches) {
            if ("wild".equalsIgnoreCase(m.getTargetKind()) || "wild_gather".equalsIgnoreCase(m.getTargetKind())) {
                if (m.getTargetId() != null) busyIds.add(m.getTargetId());
                if (m.getTargetX() != null && m.getTargetY() != null) {
                    busyCoords.add(m.getTargetX() + "," + m.getTargetY());
                }
            }
        }

        for (BattleSession b : battles) {
            if ("wild".equalsIgnoreCase(b.getTargetKind()) && b.getTargetId() != null) {
                busyIds.add(b.getTargetId());
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (WildTile w : allWilds) {
            if (Boolean.TRUE.equals(w.getOccupied()) || (w.getOccupiedBy() != null && w.getOccupiedBy() > 0)) {
                continue;
            }
            if (busyIds.contains(String.valueOf(w.getId())) || busyCoords.contains(w.getX() + "," + w.getY())) {
                continue;
            }

            WildTypeDef def = WildTypeDef.WILD_TYPES.get(w.getType());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", w.getId());
            item.put("type", w.getType());
            item.put("typeName", def != null ? def.name() : w.getType());
            item.put("icon", def != null ? def.icon() : "野");
            item.put("resType", def != null ? def.res() : null);
            item.put("level", w.getLevel());
            item.put("x", w.getX());
            item.put("y", w.getY());
            item.put("totalRes", w.getTotalRes());
            item.put("battlefield", getBattlefieldName(w.getX(), w.getY()));
            item.put("battlefieldKey", getBattlefieldKey(w.getX(), w.getY()));
            item.put("status", "SAFE_TO_DELETE");
            results.add(item);
        }
        return results;
    }

    @Transactional
    public Map<String, Object> deleteWildTiles(List<Long> ids, String adminUsername, HttpServletRequest req) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的野地");
        }
        List<March> marches = marchRepository.findAll();
        List<BattleSession> battles = battleSessionRepository.findAll();
        Set<String> busyIds = new HashSet<>();
        Set<String> busyCoords = new HashSet<>();
        for (March m : marches) {
            if ("wild".equalsIgnoreCase(m.getTargetKind()) || "wild_gather".equalsIgnoreCase(m.getTargetKind())) {
                if (m.getTargetId() != null) busyIds.add(m.getTargetId());
                if (m.getTargetX() != null && m.getTargetY() != null) {
                    busyCoords.add(m.getTargetX() + "," + m.getTargetY());
                }
            }
        }
        for (BattleSession b : battles) {
            if ("wild".equalsIgnoreCase(b.getTargetKind()) && b.getTargetId() != null) {
                busyIds.add(b.getTargetId());
            }
        }

        List<Long> deletedIds = new ArrayList<>();
        List<Long> skippedIds = new ArrayList<>();

        for (Long id : ids) {
            Optional<WildTile> opt = wildTileRepository.findById(id);
            if (opt.isEmpty()) {
                skippedIds.add(id);
                continue;
            }
            WildTile w = opt.get();
            if (Boolean.TRUE.equals(w.getOccupied())
                    || (w.getOccupiedBy() != null && w.getOccupiedBy() > 0)
                    || busyIds.contains(String.valueOf(w.getId()))
                    || busyCoords.contains(w.getX() + "," + w.getY())) {
                skippedIds.add(id);
                continue;
            }
            wildTileRepository.delete(w);
            deletedIds.add(id);
        }
        wildTileRepository.flush();

        if (!deletedIds.isEmpty()) {
            auditLogService.record(adminUsername, "DELETE_WILDS", "WILD_TILE", "BATCH",
                    String.format("批量删除 %d 块野地 (ID: %s)", deletedIds.size(), deletedIds), req);
        }

        return Map.of(
                "success", true,
                "deletedCount", deletedIds.size(),
                "deletedIds", deletedIds,
                "skippedCount", skippedIds.size(),
                "skippedIds", skippedIds
        );
    }

    @Transactional
    public Map<String, Object> clearUnoccupiedWilds(String adminUsername, HttpServletRequest req) {
        WorldMap worldMap = worldMapRepository.findFirstByOrderByIdAsc().orElse(null);
        Long worldId = worldMap != null ? worldMap.getId() : 1L;
        List<WildTile> unoccupied = wildTileRepository.findUnoccupiedByWorldId(worldId);

        List<March> marches = marchRepository.findAll();
        List<BattleSession> battles = battleSessionRepository.findAll();
        Set<String> busyIds = new HashSet<>();
        Set<String> busyCoords = new HashSet<>();
        for (March m : marches) {
            if ("wild".equalsIgnoreCase(m.getTargetKind()) || "wild_gather".equalsIgnoreCase(m.getTargetKind())) {
                if (m.getTargetId() != null) busyIds.add(m.getTargetId());
                if (m.getTargetX() != null && m.getTargetY() != null) {
                    busyCoords.add(m.getTargetX() + "," + m.getTargetY());
                }
            }
        }
        for (BattleSession b : battles) {
            if ("wild".equalsIgnoreCase(b.getTargetKind()) && b.getTargetId() != null) {
                busyIds.add(b.getTargetId());
            }
        }

        List<WildTile> toDelete = new ArrayList<>();
        int skipped = 0;
        for (WildTile w : unoccupied) {
            if (busyIds.contains(String.valueOf(w.getId())) || busyCoords.contains(w.getX() + "," + w.getY())) {
                skipped++;
            } else {
                toDelete.add(w);
            }
        }

        wildTileRepository.deleteAll(toDelete);
        wildTileRepository.flush();

        auditLogService.record(adminUsername, "CLEAR_UNOCCUPIED_WILDS", "WILD_TILE", "BATCH",
                String.format("一键清理地图无主野地共 %d 块 (跳过交战/行军目标 %d 块)", toDelete.size(), skipped), req);
        return Map.of("success", true, "deletedCount", toDelete.size(), "skippedCount", skipped);
    }

    @Transactional
    public List<Map<String, Object>> spawnWildTiles(String type, int count, Integer minLevel, Integer maxLevel, boolean clearUnoccupied, String adminUsername, HttpServletRequest req) {
        return spawnWildTiles(type, count, minLevel, maxLevel, clearUnoccupied, "all", adminUsername, req);
    }

    @Transactional
    public List<Map<String, Object>> spawnWildTiles(String type, int count, Integer minLevel, Integer maxLevel, boolean clearUnoccupied, String battlefield, String adminUsername, HttpServletRequest req) {
        if (count <= 0 || count > 200) {
            throw new IllegalArgumentException("单次生成野地数量需在 1 ~ 200 之间");
        }
        int minL = (minLevel != null && minLevel >= 1) ? minLevel : 1;
        int maxL = (maxLevel != null && maxLevel >= 1) ? maxLevel : 10;
        if (minL > maxL) {
            int tmp = minL;
            minL = maxL;
            maxL = tmp;
        }

        List<String> candidatePool;
        List<String> resTypes = List.of("grainfield", "ironworks", "oil", "rarefactory");
        List<String> allTypes = List.of("grainfield", "ironworks", "oil", "rarefactory", "forest", "hill", "swamp", "grassland", "plains", "snow", "rock");

        if ("ALL_RESOURCES".equalsIgnoreCase(type)) {
            candidatePool = resTypes;
        } else if ("ALL_TYPES".equalsIgnoreCase(type) || type == null || type.isBlank()) {
            candidatePool = allTypes;
        } else if (WildTypeDef.WILD_TYPES.containsKey(type)) {
            candidatePool = List.of(type);
        } else {
            throw new IllegalArgumentException("未知的野地类型: " + type);
        }

        WorldMap worldMap = worldMapRepository.findFirstByOrderByIdAsc().orElse(null);
        Long worldId = worldMap != null ? worldMap.getId() : 1L;

        BattlefieldBounds bounds = getBounds(battlefield, 1);

        int deletedUnoccupied = 0;
        if (clearUnoccupied) {
            if (battlefield == null || "all".equalsIgnoreCase(battlefield)) {
                deletedUnoccupied = wildTileRepository.deleteUnoccupiedByWorldId(worldId);
            } else {
                deletedUnoccupied = wildTileRepository.deleteUnoccupiedByWorldIdAndBounds(worldId, bounds.minX(), bounds.maxX(), bounds.minY(), bounds.maxY());
            }
            wildTileRepository.flush();
        }

        String mask = worldTerrainService.current();
        if (mask == null) {
            mask = WorldTerrainService.generate();
        }

        Set<String> used = new HashSet<>(worldTerrainService.occupiedCoordinates(worldId));

        int W = bounds.maxX() - bounds.minX() + 1;
        int H = bounds.maxY() - bounds.minY() + 1;
        int totalCells = W * H;
        int startOffset = ThreadLocalRandom.current().nextInt(totalCells);
        List<int[]> candidates = new ArrayList<>();

        for (int i = 0; i < totalCells && candidates.size() < count; i++) {
            int idx = (startOffset + i) % totalCells;
            int x = bounds.minX() + (idx % W);
            int y = bounds.minY() + (idx / W);
            String coordKey = x + "," + y;
            if (!used.contains(coordKey) && !WorldTerrainService.sea(mask, x, y)) {
                candidates.add(new int[]{x, y});
                used.add(coordKey);
            }
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException("所选区域（" + bounds.name() + "）陆地上已无可用空闲坐标进行撒点");
        }

        List<WildTile> toSave = new ArrayList<>();
        for (int[] coord : candidates) {
            String chosenType = candidatePool.get(ThreadLocalRandom.current().nextInt(candidatePool.size()));
            int level = ThreadLocalRandom.current().nextInt(minL, maxL + 1);
            Map<String, Integer> garrison = WorldConfig.wildGarrison(level, false);

            WildTile tile = new WildTile();
            tile.setWorldId(worldId);
            tile.setType(chosenType);
            tile.setX(coord[0]);
            tile.setY(coord[1]);
            tile.setLevel(level);
            tile.setGarrison(JsonUtil.toJson(garrison));
            tile.setScouted(false);
            tile.setOccupied(false);
            tile.setOccupiedBy(null);
            tile.setOccupiedCitySlot(0);
            tile.setTotalRes(level * WorldConfig.RES_PER_WILD_LEVEL);
            tile.setMined(0);
            toSave.add(tile);
        }

        List<WildTile> savedList = wildTileRepository.saveAll(toSave);
        wildTileRepository.flush();

        List<Map<String, Object>> results = new ArrayList<>();
        for (WildTile saved : savedList) {
            WildTypeDef def = WildTypeDef.WILD_TYPES.get(saved.getType());
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", saved.getId());
            info.put("type", saved.getType());
            info.put("typeName", def != null ? def.name() : saved.getType());
            info.put("icon", def != null ? def.icon() : "野");
            info.put("resType", def != null ? def.res() : null);
            info.put("x", saved.getX());
            info.put("y", saved.getY());
            info.put("level", saved.getLevel());
            info.put("totalRes", saved.getTotalRes());
            info.put("battlefield", getBattlefieldName(saved.getX(), saved.getY()));
            results.add(info);
        }

        String desc = String.format("在【%s】批量撒点生成 %d 个野地 (类型:%s, 等级:%d~%d, 先清理无主:%b, 清理数:%d)",
                bounds.name(), results.size(), type, minL, maxL, clearUnoccupied, deletedUnoccupied);
        auditLogService.record(adminUsername, "SPAWN_WILD_TILES", "WILD_TILE", "BATCH", desc, req);

        return results;
    }
}
