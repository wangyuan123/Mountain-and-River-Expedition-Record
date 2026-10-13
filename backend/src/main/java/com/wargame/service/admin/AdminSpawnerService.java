package com.wargame.service.admin;

import com.wargame.model.entity.BattleSession;
import com.wargame.model.entity.March;
import com.wargame.model.entity.NpcCity;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.WorldMap;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.IncomingMarchRepository;
import com.wargame.repository.MarchRepository;
import com.wargame.repository.NpcCityRepository;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.service.GameStateService;
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

    public AdminSpawnerService(NpcCityRepository npcCityRepository,
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
                               EntityManager em) {
        this.npcCityRepository = npcCityRepository;
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
    }

    @Transactional
    public List<Map<String, Object>> spawnNpcCities(int count, Integer minLevel, Integer maxLevel, String adminUsername, HttpServletRequest req) {
        throw new IllegalArgumentException("NPC城市/要塞已取消");

    }

    @Transactional
    public List<Map<String, Object>> spawnMockBots(int count, String namePrefix, Integer initialLevel, String adminUsername, HttpServletRequest req) {
        if (count <= 0 || count > 30) {
            throw new IllegalArgumentException("单次生成模拟玩家数量需在 1 ~ 30 之间");
        }
        String prefix = (namePrefix != null && !namePrefix.isBlank() && !"Bot".equalsIgnoreCase(namePrefix.trim()))
                ? namePrefix.trim()
                : "远征统帅";
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> batchNames = new HashSet<>();
        String prefixTag = prefix.endsWith("_") ? prefix : prefix + "_";

        for (int i = 0; i < count; i++) {
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

    /** 仅供管理员清理无行军或战斗引用的旧 NPC 城市。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDeletableNpcCities() {
        List<NpcCity> npcs = npcCityRepository.findAll();
        List<March> marches = marchRepository.findAll();
        List<BattleSession> battles = battleSessionRepository.findAll();

        Set<String> busyNpcIds = new HashSet<>();
        Set<String> busyCoords = new HashSet<>();

        for (March m : marches) {
            if ("npc".equalsIgnoreCase(m.getTargetKind())) {
                if (m.getTargetId() != null) busyNpcIds.add(m.getTargetId());
                if (m.getTargetX() != null && m.getTargetY() != null) {
                    busyCoords.add(m.getTargetX() + "," + m.getTargetY());
                }
            }
        }

        for (BattleSession b : battles) {
            if ("npc".equalsIgnoreCase(b.getTargetKind()) && b.getTargetId() != null) {
                busyNpcIds.add(b.getTargetId());
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (NpcCity nc : npcs) {
            if (Boolean.TRUE.equals(nc.getDefeated())) {
                // 已被占领/击败，不符合“未被占领”
                continue;
            }
            if (busyNpcIds.contains(String.valueOf(nc.getId()))) {
                // 存在行军(征服/掠夺/侦查/宣战)或交战中
                continue;
            }
            if (busyCoords.contains(nc.getX() + "," + nc.getY())) {
                // 坐标存在活跃行军
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", nc.getId());
            item.put("name", nc.getName());
            item.put("level", nc.getLevel());
            item.put("x", nc.getX());
            item.put("y", nc.getY());
            item.put("commanderName", nc.getCommanderName() != null ? nc.getCommanderName() : "无");
            item.put("status", "SAFE_TO_DELETE");
            results.add(item);
        }
        return results;
    }

    /** 清理旧 NPC 城市前重验行军和战斗引用，防止中断旧出征结算。 */
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
            if ("npc".equalsIgnoreCase(m.getTargetKind())) {
                if (m.getTargetId() != null) busyNpcIds.add(m.getTargetId());
                if (m.getTargetX() != null && m.getTargetY() != null) {
                    busyCoords.add(m.getTargetX() + "," + m.getTargetY());
                }
            }
        }
        for (BattleSession b : battles) {
            if ("npc".equalsIgnoreCase(b.getTargetKind()) && b.getTargetId() != null) {
                busyNpcIds.add(b.getTargetId());
            }
        }

        List<Long> deletedIds = new ArrayList<>();
        List<Long> skippedIds = new ArrayList<>();

        for (Long id : ids) {
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
        long now = System.currentTimeMillis();
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
            item.put("posX", bot.getCityPosX() != null ? bot.getCityPosX() : 0);
            item.put("posY", bot.getCityPosY() != null ? bot.getCityPosY() : 0);
            item.put("militaryRank", bot.getMilitaryRank());
            item.put("prestige", bot.getPrestige());
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
        long now = System.currentTimeMillis();
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
}
