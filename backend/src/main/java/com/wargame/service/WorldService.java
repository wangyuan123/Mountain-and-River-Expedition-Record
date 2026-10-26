package com.wargame.service;

import com.wargame.util.DurationText;
import com.wargame.util.PlayerNames;

import com.wargame.util.GameClock;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.constants.NewbieProtection;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 世界地图服务 - 对应 JS 中 G.World 的后端实现 (地图移动与扫描部分)。
 * <p>
 * 核心逻辑参考 js/world.js：
 * <ul>
 *   <li>{@code G.World.move}        -> {@link #move}</li>
 *   <li>{@code G.World.scan}        -> {@link #scan}</li>
 *   <li>扫描半径计算                  -> {@link #calcScanRadius}</li>
 *   <li>附近城池查询                  -> {@link #getNearbyCities}</li>
 *   <li>{@code G.World.declareWar}  -> {@link #declareWar}</li>
 *   <li>战争状态查询                  -> {@link #getWarStatus}</li>
 *   <li>{@code G.World.attack(scout)} -> {@link #scout}</li>
 *   <li>所属野地查询                  -> {@link #getOwnedWildTiles}</li>
 * </ul>
 */
@Service
public class WorldService {

    @org.springframework.beans.factory.annotation.Autowired private AccountService accounts;
    @org.springframework.beans.factory.annotation.Autowired private GuildRelationService guildRelations;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired private CityStateRepository cityStates;
    @org.springframework.beans.factory.annotation.Autowired private MarchService marchService;

    private final PlayerRepository playerRepository;
    private final WorldMapRepository worldMapRepository;
    private final PlayerCityRepository playerCityRepository;
    private final WildTileRepository wildTileRepository;
    private final BanditRepository banditRepository;
    private final BuildingRepository buildingRepository;
    private final TechnologyRepository technologyRepository;
    private final MarchRepository marchRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final com.wargame.service.quest.QuestService questService;
    private final ChatService chatService;
    private final MailService mailService;

    /** 宣战准备时间 (2小时) - 对应 JS var prepareSec = 2 * 3600 */
    private static final long WAR_PREPARE_MS = 2 * 3600 * 1000L;
    /** 交战持续时间 (48小时) - 对应 JS var warSec = 48 * 3600 */
    private static final long WAR_DURATION_MS = 48 * 3600 * 1000L;

    public WorldService(PlayerRepository playerRepository,
                        WorldMapRepository worldMapRepository,
                        PlayerCityRepository playerCityRepository,
                        WildTileRepository wildTileRepository,
                        BanditRepository banditRepository,
                        BuildingRepository buildingRepository,
                        TechnologyRepository technologyRepository,
                        MarchRepository marchRepository,
                        ArmyUnitRepository armyUnitRepository,
                        com.wargame.service.quest.QuestService questService,
                        ChatService chatService,
                        MailService mailService) {
        this.playerRepository = playerRepository;
        this.worldMapRepository = worldMapRepository;
        this.playerCityRepository = playerCityRepository;
        this.wildTileRepository = wildTileRepository;
        this.banditRepository = banditRepository;
        this.buildingRepository = buildingRepository;
        this.technologyRepository = technologyRepository;
        this.marchRepository = marchRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.questService = questService;
        this.chatService = chatService;
        this.mailService = mailService;
    }

    // ================================================================
    //  move - 对应 JS G.World.move(dx, dy)
    // ================================================================

    @Transactional
    public Map<String, Object> move(Long playerId, String direction) {
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null) {
            result.put("success", false);
            result.put("message", "玩家不存在");
            return result;
        }

        // Direction mapping: up -> dy=-1, down -> dy=1, left -> dx=-1, right -> dx=1
        int dx = 0, dy = 0;
        switch (direction) {
            case "up"    -> dy = -1;
            case "down"  -> dy = 1;
            case "left"  -> dx = -1;
            case "right" -> dx = 1;
            default -> {
                result.put("success", false);
                result.put("message", "无效方向: " + direction);
                return result;
            }
        }

        int px = player.getPosX() != null ? player.getPosX() : 0;
        int py = player.getPosY() != null ? player.getPosY() : 0;
        int size = worldMapRepository.findFirstByOrderByIdAsc().map(w -> w.getSize() != null ? w.getSize() : WorldConfig.WORLD_SIZE).orElse(WorldConfig.WORLD_SIZE);

        // JS: var nx = G.clamp(s.world.pos.x + dx, 0, W.size - 1)
        int nx = Math.max(0, Math.min(size - 1, px + dx));
        int ny = Math.max(0, Math.min(size - 1, py + dy));

        // JS: if (nx === pos.x && ny === pos.y) -> 边界
        if (nx == px && ny == py) {
            result.put("success", false);
            result.put("message", "已到达地图边界");
            return result;
        }

        player.setPosX(nx);
        player.setPosY(ny);
        playerRepository.save(player);

        result.put("success", true);
        result.put("message", "移动到 (" + nx + "," + ny + ")");
        result.put("posX", nx);
        result.put("posY", ny);
        // 移动成功后直接返回最新坐标。
        return result;
    }

    // ================================================================
    //  scan - 对应 JS G.World.scan
    // ================================================================

    @Transactional
    public Map<String, Object> scan(Long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // JS: var radarLv = s.buildings.radar || 0; if (radarLv <= 0)
        int radarLv = buildingLevel(playerId, "radar");
        if (radarLv <= 0) {
            result.put("success", false);
            result.put("message", "需建造城防雷达才能扫描");
            return result;
        }

        int radius = calcScanRadius(playerId);

        // JS: s.world._scan = { r: r, at: Date.now() }
        result.put("success", true);
        result.put("message", "扫描半径 " + radius + " 完成");
        result.put("radius", radius);
        result.put("nearby", getNearbyCities(playerId));

        // 主线任务进度钩子
        try {
            questService.onEvent(playerId, "MAP_SCAN", null, 1);
        } catch (Exception ignored) {}

        return result;
    }

    // ================================================================
    //  calcScanRadius - 扫描半径计算
    //  对应 JS: D.world.viewRadius + radarLv（侦察技术不增加扫描半径）
    // ================================================================

    public int calcScanRadius(Long playerId) {
        int radarLv = buildingLevel(playerId, "radar");
        return WorldConfig.VIEW_RADIUS + radarLv;
    }

    /** 按坐标查询地图目标，仅返回目标存在性和基础公开信息，不绕过侦查详情权限。 */
    public Map<String, Object> findAtCoordinate(Long playerId, int x, int y) {
        Map<String, Object> result = new LinkedHashMap<>();
        Long worldId = worldMapRepository.findFirstByOrderByIdAsc().map(WorldMap::getId).orElse(null);
        if (worldId == null) return result;

        Player realPlayer = playerRepository.findByCityPosXAndCityPosY(x, y).orElse(null);
        if (realPlayer != null && !realPlayer.deletionDue(System.currentTimeMillis())) {
            PlayerCity realCity = playerCityRepository.findByOwnerId(realPlayer.getId()).stream()
                    .filter(city -> x == city.getX() && y == city.getY())
                    .findFirst().orElse(null);
            if (realCity != null) {
                result.put("kind", "player");
                result.put("id", realCity.getId());
                result.put("name", realPlayer.getCityName() == null || realPlayer.getCityName().isBlank()
                        ? "新城市" : realPlayer.getCityName());
                result.put("level", realPlayer.getLevel());
                result.put("prestige", realPlayer.getPrestige());
                boolean selfCity = realPlayer.getId().equals(playerId);
                boolean relatedWar = !selfCity && playerId.equals(realPlayer.getWarAgainstId());
                result.put("ownerId", realPlayer.getId());
                result.put("guildmate", guildRelations.areGuildmates(playerId, realPlayer.getId()));
                result.put("selfCity", selfCity);
                result.put("warAt", relatedWar && realPlayer.getWarAt() != null ? realPlayer.getWarAt() : 0L);
                result.put("warEndAt", relatedWar && realPlayer.getWarEndAt() != null ? realPlayer.getWarEndAt() : 0L);
                result.put("realPlayer", true);
                result.put("x", x);
                result.put("y", y);
                result.put("distance", manhattanDist(currentX(playerId), currentY(playerId), x, y));
                return result;
            }
        }

        PlayerCity playerCity = playerCityRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId, x, x, y, y).stream()
                .filter(city -> x == city.getX() && y == city.getY())
                .findFirst().orElse(null);
        if (playerCity != null) {
            Player owner = playerCity.getOwnerId() != null
                    ? playerRepository.findById(playerCity.getOwnerId()).orElse(null) : null;
            if (owner != null && owner.deletionDue(System.currentTimeMillis())) return result;
            boolean simulatedNpc = owner == null;
            result.put("kind", simulatedNpc ? "simulated_npc" : "player");
            result.put("id", playerCity.getId());
            result.put("name", playerCity.getName());
            result.put("level", playerCity.getLevel());
            result.put("ownerId", simulatedNpc ? null : playerCity.getOwnerId());
            result.put("prestige", owner != null && owner.getPrestige() != null ? owner.getPrestige() : 0);
            result.put("guildmate", !simulatedNpc && guildRelations.areGuildmates(playerId, playerCity.getOwnerId()));
            result.put("simulatedNpc", simulatedNpc);
            result.put("selfCity", !simulatedNpc && playerCity.getOwnerId().equals(playerId));
            result.put("warAt", 0L);
            result.put("warEndAt", 0L);
            result.put("x", x);
            result.put("y", y);
            result.put("distance", manhattanDist(currentX(playerId), currentY(playerId), x, y));
            return result;
        }

        WildTile wildTile = wildTileRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId, x, x, y, y).stream()
                .filter(tile -> x == tile.getX() && y == tile.getY())
                .findFirst().orElse(null);
        if (wildTile != null) {
            result.put("kind", "wild");
            result.put("id", wildTile.getId());
            result.put("level", wildTile.getLevel());
            result.put("x", x);
            result.put("y", y);
            result.put("distance", manhattanDist(currentX(playerId), currentY(playerId), x, y));
        }
        return result;
    }

    private int currentX(Long playerId) {
        return playerRepository.findById(playerId).map(p -> p.getPosX() == null ? 0 : p.getPosX()).orElse(0);
    }

    private int currentY(Long playerId) {
        return playerRepository.findById(playerId).map(p -> p.getPosY() == null ? 0 : p.getPosY()).orElse(0);
    }


    //  对应 JS renderView 中 dist(px, py, target.x, target.y) <= scanR
    // ================================================================

    public Map<String, Object> getNearbyCities(Long playerId) {
        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null) return Collections.emptyMap();

        int px = player.getPosX() != null ? player.getPosX() : 0;
        int py = player.getPosY() != null ? player.getPosY() : 0;
        int scanR = calcScanRadius(playerId);

        Long worldId = worldMapRepository.findFirstByOrderByIdAsc()
                .map(WorldMap::getId).orElse(null);
        if (worldId == null) return Collections.emptyMap();
        int worldSize = worldMapRepository.findById(worldId).map(w -> w.getSize() != null ? w.getSize() : WorldConfig.WORLD_SIZE).orElse(WorldConfig.WORLD_SIZE);

        Map<String, Object> nearby = new LinkedHashMap<>();

        nearby.put("npcCities", new ArrayList<>());

        // Player cities and ownerless legacy cities, which are simulated NPCs.
        List<Map<String, Object>> playerCities = new ArrayList<>();
        List<Map<String, Object>> simulatedNpcCities = new ArrayList<>();
        List<PlayerCity> pcAll = playerCityRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId,
                Math.max(0, px - scanR), Math.min(worldSize - 1, px + scanR),
                Math.max(0, py - scanR), Math.min(worldSize - 1, py + scanR));
        Map<String, Player> byCoordinates = new HashMap<>();
        Map<Long, Player> byId = new HashMap<>();
        playerRepository.findByCityPosXBetweenAndCityPosYBetween(
                Math.max(0, px - scanR), Math.min(worldSize - 1, px + scanR),
                Math.max(0, py - scanR), Math.min(worldSize - 1, py + scanR))
                .forEach(owner -> {
                    byCoordinates.put(owner.getCityPosX() + "," + owner.getCityPosY(), owner);
                    byId.put(owner.getId(), owner);
                });
        Set<Long> ownerIds = new HashSet<>();
        pcAll.stream().map(PlayerCity::getOwnerId).filter(Objects::nonNull)
                .filter(id -> !byId.containsKey(id)).forEach(ownerIds::add);
        if (!ownerIds.isEmpty()) playerRepository.findAllById(ownerIds).forEach(owner -> byId.put(owner.getId(), owner));
        for (int i = 0; i < pcAll.size(); i++) {
            PlayerCity pc = pcAll.get(i);
            if (manhattanDist(px, py, pc.getX(), pc.getY()) <= scanR) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("idx", i);
                m.put("id", pc.getId());
                m.put("name", pc.getName());
                m.put("level", pc.getLevel());
                m.put("x", pc.getX());
                m.put("y", pc.getY());
                m.put("prestige", pc.getPrestige());
                Long ownerId = pc.getOwnerId();
                Player coordinateOwner = byCoordinates.get(pc.getX() + "," + pc.getY());
                if (coordinateOwner != null) {
                    ownerId = coordinateOwner.getId();
                }
                Player owner = ownerId != null ? byId.get(ownerId) : null;
                if (owner != null && owner.deletionDue(System.currentTimeMillis())) continue;
                if (owner == null) {
                    m.put("simulatedNpc", true);
                    m.put("distance", manhattanDist(px, py, pc.getX(), pc.getY()));
                    simulatedNpcCities.add(m);
                    continue;
                }
                boolean selfCity = ownerId.equals(playerId);
                boolean relatedWar = !selfCity && playerId.equals(owner.getWarAgainstId());
                m.put("ownerId", ownerId);
                m.put("playerName", owner != null ? PlayerNames.commanderName(owner) : "");
                m.put("prestige", owner != null && owner.getPrestige() != null ? owner.getPrestige() : 0);
                m.put("selfCity", selfCity);
                m.put("guildmate", guildRelations.areGuildmates(playerId, ownerId));
                int slot = pc.getCitySlot() != null ? pc.getCitySlot() : 0;
                buildingRepository.findByPlayerIdAndCitySlotAndType(ownerId, slot, "wall").stream()
                        .map(Building::getLevel).filter(Objects::nonNull).findFirst()
                        .ifPresent(lvl -> m.put("wallLevel", lvl));
                m.put("warAt", relatedWar && owner.getWarAt() != null ? owner.getWarAt() : 0L);
                m.put("warEndAt", relatedWar && owner.getWarEndAt() != null ? owner.getWarEndAt() : 0L);
                m.put("distance", manhattanDist(px, py, pc.getX(), pc.getY()));
                playerCities.add(m);
            }
        }
        nearby.put("playerCities", playerCities);
        nearby.put("simulatedNpcCities", simulatedNpcCities);

        // Wild tiles (JS: s.world.wildTiles.forEach)
        List<Map<String, Object>> wildTiles = new ArrayList<>();
        List<WildTile> wtAll = wildTileRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId,
                Math.max(0, px - scanR), Math.min(worldSize - 1, px + scanR),
                Math.max(0, py - scanR), Math.min(worldSize - 1, py + scanR));
        for (int i = 0; i < wtAll.size(); i++) {
            WildTile wt = wtAll.get(i);
            if (!wt.isDormant() && manhattanDist(px, py, wt.getX(), wt.getY()) <= scanR) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("idx", i);
                m.put("id", wt.getId());
                m.put("type", wt.getType());
                m.put("level", wt.getLevel());
                m.put("citySlot", wt.getOccupiedCitySlot() == null ? 0 : wt.getOccupiedCitySlot());
                m.put("x", wt.getX());
                m.put("y", wt.getY());
                m.put("scouted", wt.getScouted() != null && wt.getScouted());
                m.put("occupied", wt.getOccupied() != null && wt.getOccupied());
                m.put("totalRes", wt.getTotalRes() != null ? wt.getTotalRes() : 0);
                m.put("mined", wt.getMined() != null ? wt.getMined() : 0);
                m.put("distance", manhattanDist(px, py, wt.getX(), wt.getY()));
                wildTiles.add(m);
            }
        }
        nearby.put("wildTiles", wildTiles);

        // Bandits (JS: s.world.bandits.forEach - not explicitly in renderView nearby,
        //  but included for completeness)
        List<Map<String, Object>> bandits = new ArrayList<>();
        List<Bandit> bAll = banditRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId,
                Math.max(0, px - scanR), Math.min(worldSize - 1, px + scanR),
                Math.max(0, py - scanR), Math.min(worldSize - 1, py + scanR));
        for (int i = 0; i < bAll.size(); i++) {
            Bandit b = bAll.get(i);
            if (Boolean.TRUE.equals(b.getDefeated())) continue;
            if (manhattanDist(px, py, b.getX(), b.getY()) <= scanR) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("idx", i);
                m.put("id", b.getId());
                m.put("name", b.getName());
                m.put("level", b.getLevel());
                m.put("x", b.getX());
                m.put("y", b.getY());
                m.put("defeated", b.getDefeated() != null && b.getDefeated());
                m.put("distance", manhattanDist(px, py, b.getX(), b.getY()));
                bandits.add(m);
            }
        }
        nearby.put("bandits", bandits);

        return nearby;
    }

    // ================================================================
    //  declareWar - 对应 JS G.World.declareWar
    // ================================================================

    @Transactional
    public Map<String, Object> declareWar(Long playerId, Long targetCityId) {
        return declareWar(playerId, targetCityId, false);
    }

    /** 宣战退出账号新手保护须明确确认，且只在宣战全部校验成功后生效。 */
    @Transactional
    public Map<String, Object> declareWar(Long playerId, Long targetCityId, Boolean abandonNewbieProtection) {
        Map<String, Object> result = new LinkedHashMap<>();

        PlayerCity target = playerCityRepository.findById(targetCityId).orElse(null);
        if (target == null) {
            result.put("success", false);
            result.put("message", "目标城市不存在");
            return result;
        }

        Player attacker = playerRepository.findById(playerId).orElse(null);
        if (attacker == null) {
            result.put("success", false);
            result.put("message", "玩家不存在");
            return result;
        }

        Long defenderId = target.getOwnerId();
        if (defenderId == null || !playerRepository.existsById(defenderId)) {
            result.put("success", false);
            result.put("message", "该城市为模拟 NPC, 无法宣战");
            return result;
        }
        if (defenderId.equals(playerId)) {
            result.put("success", false);
            result.put("message", "不能向自己的城市宣战");
            return result;
        }

        // 与开盾使用同一玩家锁；仅检查发起城和被宣战城，不扩散到其他城或野地。
        attacker = accounts.lockPlayer(playerId);
        Player defender = accounts.lockPlayer(defenderId);
        long shieldCheckAt = GameClock.now();
        NewbieProtection.start(attacker, shieldCheckAt);
        boolean ownShield = cityStates.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId))
                .map(cs -> cs.getShieldUntil() != null && cs.getShieldUntil() > shieldCheckAt).orElse(false);
        boolean targetShield = cityStates.findByPlayerIdAndCitySlot(defenderId, java.util.Objects.requireNonNullElse(target.getCitySlot(), 0))
                .map(cs -> cs.getShieldUntil() != null && cs.getShieldUntil() > shieldCheckAt).orElse(false);
        targetShield = targetShield || NewbieProtection.until(defender, shieldCheckAt) > shieldCheckAt;
        if (ownShield || targetShield) {
            result.put("success", false);
            result.put("message", ownShield ? "当前城市护盾生效，无法向其他玩家宣战" : "目标城市护盾生效，无法宣战");
            return result;
        }

        String guildRelation = guildRelations.relationshipBetweenPlayers(playerId, defenderId);
        if (GuildRelationService.HOSTILE.equals(guildRelation)) {
            result.put("success", false);
            result.put("message", "敌对军团成员可直接征服或掠夺，无需宣战");
            return result;
        }
        if (GuildRelationService.FRIENDLY.equals(guildRelation)) {
            result.put("success", false);
            result.put("message", "友好军团成员之间不能宣战");
            return result;
        }

        if (defender == null) {
            result.put("success", false);
            result.put("message", "目标玩家不存在");
            return result;
        }
        if (defender.deletionDue(System.currentTimeMillis())) throw new IllegalArgumentException("目标城池已失效");

        long now = GameClock.now();

        // 双向重宣检查：备战期(now<warAt) 与 交战期(warAt<=now<warEndAt) 都拒绝
        long attackerWarEnd = attacker.getWarEndAt() != null ? attacker.getWarEndAt() : 0L;
        long attackerWarAt   = attacker.getWarAt()    != null ? attacker.getWarAt()    : 0L;
        long defenderWarEnd  = defender.getWarEndAt() != null ? defender.getWarEndAt() : 0L;
        long defenderWarAt   = defender.getWarAt()    != null ? defender.getWarAt()    : 0L;

        if (attackerWarEnd > now && defenderId.equals(attacker.getWarAgainstId())) {
            String phase = now < attackerWarAt ? "备战" : "交战";
            result.put("success", false);
            result.put("message", "你已对该玩家处于" + phase + "状态,无法重复宣战");
            return result;
        }
        if (defenderWarEnd > now && playerId.equals(defender.getWarAgainstId())) {
            String phase = now < defenderWarAt ? "备战" : "交战";
            result.put("success", false);
            result.put("message", "对方正处于" + phase + "状态(已被你宣战),无法再次宣战");
            return result;
        }
        // 兼容旧数据：PlayerCity 上仍可能记录着战时状态
        long legacyWarEnd = target.getWarEndAt() != null ? target.getWarEndAt() : 0L;
        if (legacyWarEnd > now) {
            result.put("success", false);
            result.put("message", "目标正处于战时状态,无法重复宣战");
            return result;
        }

        long warAt = now + WAR_PREPARE_MS;
        long warEndTime = warAt + WAR_DURATION_MS;

        NewbieProtection.requireConfirmation(attacker, abandonNewbieProtection, now);
        if (NewbieProtection.until(attacker, now) > now) attacker.setNewbieProtectionEnded(true);

        // 写双方玩家
        attacker.setWarAt(warAt);
        attacker.setWarEndAt(warEndTime);
        attacker.setWarAgainstId(defenderId);
        defender.setWarAt(warAt);
        defender.setWarEndAt(warEndTime);
        defender.setWarAgainstId(playerId);
        playerRepository.save(attacker);
        playerRepository.save(defender);

        // 兼容旧 player_cities 字段，避免历史数据丢失
        target.setWarAt(warAt);
        target.setWarEndAt(warEndTime);
        playerCityRepository.save(target);

        // 主线任务进度钩子
        try {
            questService.onEvent(playerId, "WAR_DECLARE", null, 1);
        } catch (Exception ignored) {}

        // 世界频道广播
        try {
            String attackerName = PlayerNames.commanderName(attacker);
            String defenderName = PlayerNames.commanderName(defender);
            String content = "⚔ " + attackerName + " 对玩家「" + defenderName + "」宣战!";
            chatService.sendSystem(content);
        } catch (Exception ignored) {}

        // 邮件通知双方
        if (mailService != null) {
            try {
                String attackerName = PlayerNames.commanderName(attacker);
                String defenderName = PlayerNames.commanderName(defender);
                String targetName = target.getName();
                String targetCoord = (target.getX() != null && target.getY() != null)
                        ? " (" + target.getX() + ", " + target.getY() + ")" : "";
                // 邮件展示发送时的真实预计时刻；后续停服仍以游戏倒计时顺延。
                long wallOffset = System.currentTimeMillis() - GameClock.now();
                String startTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(warAt + wallOffset)) + "（停服顺延）";
                String endTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(warEndTime + wallOffset)) + "（停服顺延）";

                // 1. 发给宣战发起方 (attacker)
                String attackerSubject = "【宣战公告】已对玩家「" + defenderName + "」宣战";
                String attackerBody = "指挥官，您已正式向玩家「" + defenderName + "」的城市「" + targetName + "」" + targetCoord + "发起宣战！\n\n"
                        + "【战争时间安排】\n"
                        + "· 备战时间：" + DurationText.millis(WAR_PREPARE_MS) + "（倒计时中）\n"
                        + "· 开战时间：" + startTime + "\n"
                        + "· 交战时间：" + DurationText.millis(WAR_DURATION_MS) + "\n"
                        + "· 终战时间：" + endTime + "\n\n"
                        + "【战术作战指示】\n"
                        + "1. 备战期间双方处于戒备状态，无法发动直接军事进攻，但可派遣侦察部队收集目标城防情报。\n"
                        + "2. 备战倒计时结束后，战争状态正式激活，即可派出作战部队发起进攻或掠夺。\n"
                        + "3. 宣战后双方城池均进入战争状态，对方亦可发起进攻与反击，请务必调派得力军官与主力部队留守城防！";
                mailService.sendSystem(playerId, "系统", "combat", attackerSubject, attackerBody, List.of());

                // 2. 发给被宣战方 (defender)
                if (defenderId != null) {
                    String defenderSubject = "【战争警报】玩家「" + attackerName + "」向你宣战！";
                    String defenderBody = "警报！玩家「" + attackerName + "」已向你的城市「" + targetName + "」" + targetCoord + "发起宣战！\n\n"
                            + "【战争时间安排】\n"
                            + "· 备战时间：" + DurationText.millis(WAR_PREPARE_MS) + "\n"
                            + "· 开战时间：" + startTime + "\n"
                            + "· 交战时间：" + DurationText.millis(WAR_DURATION_MS) + "\n"
                            + "· 终战时间：" + endTime + "\n\n"
                            + "【防守应对建议】\n"
                            + "1. 备战期间敌军尚无法发起军事进攻，请抓紧时间检视并巩固城防设施。\n"
                            + "2. 请尽快调配精锐部队与强力军官进驻城防，或联络盟友准备联防与反击！";
                    mailService.sendSystem(defenderId, "系统", "combat", defenderSubject, defenderBody, List.of());
                }
            } catch (Exception ignored) {}
        }

        result.put("success", true);
        result.put("message", "已宣战! " + DurationText.millis(WAR_PREPARE_MS) + "后可交战,持续" + DurationText.millis(WAR_DURATION_MS));
        result.put("warAt", warAt);
        result.put("warEndAt", warEndTime);
        return result;
    }

    // ================================================================
    //  getWarStatus - 返回战争阶段
    // ================================================================

    public Map<String, Object> getWarStatus(Long playerId, Long targetCityId) {
        Map<String, Object> result = new LinkedHashMap<>();

        PlayerCity target = playerCityRepository.findById(targetCityId).orElse(null);
        if (target == null) {
            result.put("phase", "none");
            result.put("message", "目标城市不存在");
            return result;
        }

        long now = GameClock.now();

        // 优先取真实玩家的 warAt/warEndAt（双向宣战）；
        // 若城市无主（例如 NPC / 老数据），回退到 PlayerCity 上的旧字段。
        Long defenderId = target.getOwnerId();
        long warAt;
        long warEndAt;
        if (defenderId != null && GuildRelationService.HOSTILE.equals(guildRelations.relationshipBetweenPlayers(playerId, defenderId))) {
            result.put("phase", "combat");
            result.put("warAt", now);
            result.put("warEndAt", 0L);
            result.put("remaining", 0L);
            result.put("guildRelation", GuildRelationService.HOSTILE);
            return result;
        }
        if (defenderId != null) {
            Player defender = playerRepository.findById(defenderId).orElse(null);
            if (defender != null) {
                warAt = defender.getWarAt() != null ? defender.getWarAt() : 0L;
                warEndAt = defender.getWarEndAt() != null ? defender.getWarEndAt() : 0L;
            } else {
                warAt = target.getWarAt() != null ? target.getWarAt() : 0L;
                warEndAt = target.getWarEndAt() != null ? target.getWarEndAt() : 0L;
            }
        } else {
            warAt = target.getWarAt() != null ? target.getWarAt() : 0L;
            warEndAt = target.getWarEndAt() != null ? target.getWarEndAt() : 0L;
        }

        String phase;
        if (warAt == 0 || warEndAt == 0) {
            phase = "peace";
        } else if (now < warAt) {
            phase = "countdown";
        } else if (now < warEndAt) {
            phase = "combat";
        } else {
            phase = "ended";
        }

        result.put("phase", phase);
        result.put("warAt", warAt);
        result.put("warEndAt", warEndAt);

        long remain = switch (phase) {
            case "countdown" -> warAt - now;
            case "combat" -> warEndAt - now;
            default -> 0;
        };
        result.put("remaining", remain);

        return result;
    }

    // ================================================================
    //  scout - 对应 JS G.World.attack(kind, idx, 'scout') + launchDispatch
    // ================================================================

    @Transactional
    public Map<String, Object> scout(Long playerId, int targetIdx, String targetKind) {
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null) {
            result.put("success", false);
            result.put("message", "玩家不存在");
            return result;
        }

        Long worldId = worldMapRepository.findFirstByOrderByIdAsc()
                .map(WorldMap::getId).orElse(null);
        if (worldId == null) {
            result.put("success", false);
            result.put("message", "世界未初始化");
            return result;
        }

        // Resolve target (JS: target lookup by kind and idx)
        int targetX, targetY;
        String targetName, targetId;
        boolean targetDefeated;

        switch (targetKind) {
            case "bandit" -> {
                List<Bandit> bandits = banditRepository.findByWorldId(worldId);
                if (targetIdx < 0 || targetIdx >= bandits.size()) {
                    result.put("success", false);
                    result.put("message", "目标不存在");
                    return result;
                }
                Bandit b = bandits.get(targetIdx);
                targetX = b.getX();
                targetY = b.getY();
                targetName = b.getName();
                targetId = "b_" + targetIdx;
                targetDefeated = b.getDefeated() != null && b.getDefeated();
            }
            case "npc" -> {
                result.put("success", false);
                result.put("message", "NPC城市已取消");
                return result;
            }
            case "player", "simulated_npc" -> {
                List<PlayerCity> pcs = playerCityRepository.findByWorldId(worldId).stream()
                        .filter(p -> "simulated_npc".equals(targetKind)
                                ? p.getOwnerId() == null || !playerRepository.existsById(p.getOwnerId())
                                : p.getOwnerId() != null && playerRepository.existsById(p.getOwnerId()))
                        .toList();
                if (targetIdx < 0 || targetIdx >= pcs.size()) {
                    result.put("success", false);
                    result.put("message", "目标不存在");
                    return result;
                }
                PlayerCity p = pcs.get(targetIdx);
                targetX = p.getX();
                targetY = p.getY();
                targetName = p.getName();
                targetId = ("simulated_npc".equals(targetKind) ? "sn_" : "p_") + targetIdx;
                targetDefeated = false;
            }
            default -> {
                result.put("success", false);
                result.put("message", "无效的目标类型: " + targetKind);
                return result;
            }
        }

        // JS: if (target.defeated) { G.toast('目标已被击败,等待刷新'); return; }
        if (targetDefeated) {
            result.put("success", false);
            result.put("message", "目标已被击败,等待刷新");
            return result;
        }

        // Get available scout units
        List<ArmyUnit> scoutUnits = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), "scout");
        int scoutCount = 0;
        if (scoutUnits != null && !scoutUnits.isEmpty()) {
            scoutCount = scoutUnits.get(0).getCount() != null ? scoutUnits.get(0).getCount() : 0;
        }
        if (scoutCount <= 0) {
            result.put("success", false);
            result.put("message", "城内无侦察机可用,请先制造侦察机");
            return result;
        }

        // JS default for scout: value = Math.min(have, 1) -> send 1 scout
        int sendCount = Math.min(scoutCount, 1);

        // Deduct scout units (JS: s.army[uid2] -= customArmy[uid2])
        scoutUnits.get(0).setCount(scoutCount - sendCount);
        armyUnitRepository.save(scoutUnits.get(0));

        // Calculate march time (JS: marchSec = ceil(marchDist * secPerGrid / spd))
        int px = cityScope.economy(playerId).getCityPosX();
        int py = cityScope.economy(playerId).getCityPosY();
        // 快捷侦查与出征准备使用相同的直线距离，避免绕过路线预览后计时不同。
        double marchDist = Math.hypot(targetX - px, targetY - py);
        UnitDef scoutDef = GameData.UNITS.get("scout");
        int airLv = getTechLevel(playerId, "air_engine");
        int apronLv = buildingLevel(playerId, "apron");
        double spd = (scoutDef != null ? Math.max(1, scoutDef.spd()) : 1) * (1.0 + 0.05 * airLv + 0.03 * apronLv);
        int marchSec = (int) Math.ceil((double) marchDist * WorldConfig.MARCH_SEC_PER_GRID / spd);
        if (marchSec < 1) marchSec = 1;

        long now = GameClock.now();

        // Create march (JS: s.world.marches.push(march))
        March march = new March();
        march.setPlayerId(playerId);
        march.setCitySlot(cityScope.slot(playerId));
        march.setTargetKind(targetKind);
        march.setTargetId(targetId);
        march.setTargetName(targetName);
        march.setTargetX(targetX);
        march.setTargetY(targetY);
        march.setFromX(px);
        march.setFromY(py);
        march.setDistance(marchDist);
        march.setRouteMode("air");
        march.setRouteData(JsonUtil.toJson(List.of(List.of(px, py), List.of(targetX, targetY))));
        march.setAction("scout");
        march.setArmy(JsonUtil.toJson(Map.of("scout", sendCount)));
        march.setCommanderId(null);
        march.setCarryRes("{}");
        march.setStartAt(now);
        march.setArriveAt(now + marchSec * 1000L);
        march.setReturning(false);
        march.setGathering(false);
        march.setGatherEndAt(0L);
        march.setGatherAmount(0);
        march.setGatherRes(null);
        marchRepository.save(march);

        result.put("success", true);
        result.put("message", "侦查部队出征! 距" + String.format(Locale.ROOT, "%.2f", marchDist) + "格 约" + DurationText.seconds(marchSec) + "后到达");
        result.put("marchId", march.getId());
        result.put("arriveAt", march.getArriveAt());
        return result;
    }

    // ================================================================
    //  getOwnedWildTiles - 返回当前城市已占领的野地
    // ================================================================

    public List<Map<String, Object>> getOwnedWildTiles(Long playerId) {
        Long worldId = worldMapRepository.findFirstByOrderByIdAsc()
                .map(WorldMap::getId).orElse(null);
        if (worldId == null) return Collections.emptyList();

        List<Map<String, Object>> owned = new ArrayList<>();

        List<WildTile> tiles = wildTileRepository.findByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(playerId, cityScope.slot(playerId));

        if (tiles != null) {
            for (WildTile wt : tiles) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", wt.getId());
                m.put("type", wt.getType());
                m.put("x", wt.getX());
                m.put("y", wt.getY());
                m.put("level", wt.getLevel());
                m.put("totalRes", wt.getTotalRes() != null ? wt.getTotalRes() : 0);
                m.put("mined", wt.getMined() != null ? wt.getMined() : 0);
                int remaining = (wt.getTotalRes() != null ? wt.getTotalRes() : 0)
                        - (wt.getMined() != null ? wt.getMined() : 0);
                m.put("remaining", remaining);
                m.put("gathering", Boolean.TRUE.equals(wt.getGathering()));
                m.put("gatherMode", "manual");
                m.put("gatherHarvested", wt.getGatherHarvested());
                m.put("gatherRes", wt.getGatherRes());
                owned.add(m);
            }
        }

        return owned;
    }

    // ================================================================
    //  abandonWild - 对应 JS world.js abandonWild(idx)
    //  放弃野地: occupied=false, scouted=false, mined=0
    // ================================================================

    /** 手动释放本人野地；采集任务需先召回，普通驻军创建返程行军并在抵达后归还兵力。 */
    @Transactional
    public Map<String, Object> abandonWild(Long playerId, Long wildTileId) {
        Map<String, Object> result = new LinkedHashMap<>();

        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        WildTile wt = wildTileRepository.lockById(wildTileId).orElse(null);
        if (wt == null) {
            result.put("success", false);
            result.put("message", "野地不存在");
            return result;
        }

        if (!Boolean.TRUE.equals(wt.getOccupied()) || !playerId.equals(wt.getOccupiedBy())) {
            result.put("success", false);
            result.put("message", "该野地未被你占领");
            return result;
        }

        String abandonReason = wildAbandonReason(wt);
        if (abandonReason != null) {
            result.put("success", false);
            result.put("message", abandonReason);
            return result;
        }

        boolean hasGarrison = JsonUtil.parseIntMap(wt.getGarrison()).values().stream().anyMatch(count -> count > 0);
        if (hasGarrison) {
            // 必须在清除归属和路线前创建返程；无历史路线的驻军按领地所属城市补全，不能直接计入城内兵力。
            try (var ignored = cityScope.enter(playerId, Objects.requireNonNullElse(wt.getOccupiedCitySlot(), 0))) {
                marchService.recallWild(playerId, wildTileId);
            }
        }

        wt.setOccupied(false);
        wt.setScouted(false);
        // 兼容没有耗尽时间的旧存档；放弃后立即隐藏，等待异地重生。
        if (wt.getDepletedAt() == null && wt.getTotalRes() != null
                && (wt.getTotalRes() <= 0 || (wt.getMined() != null && wt.getMined() >= wt.getTotalRes())))
            wt.setDepletedAt(GameClock.now());
        if (wt.getDepletedAt() == null) wt.setMined(0);
        wt.setOccupiedBy(null);
        wt.setOccupiedCitySlot(0);
        wt.setGarrison("{}");
        wt.setGathering(false);
        wt.setGatherStartAt(0L);
        wt.setGatherEndAt(0L);
        wt.setGatherLoad(0);
        wt.setGatherRes(null);
        wt.setGatherMode("manual");
        wt.setGatherCitySlot(0);
        wt.setGatherHarvested(null);
        wt.setGarrisonRoutes(null);
        wildTileRepository.save(wt);

        result.put("success", true);
        result.put("message", hasGarrison
                ? "已放弃该领地，驻军已按原路线返回各自出发城市，抵达后归还兵力"
                : "已放弃该领地");
        return result;
    }

    /** 保留锁定查询的释放边界，按未收获、已收获待回城、去程及历史未结算返程区分提示。 */
    private String wildAbandonReason(WildTile tile) {
        List<Long> blockerIds = marchRepository.findWildGatherReleaseBlockerIds(String.valueOf(tile.getId()));
        List<March> pending = marchRepository.findAllById(blockerIds).stream()
                .filter(march -> !Boolean.TRUE.equals(march.getReturning())).toList();
        // 同一野地可能同时有驻军及多支独立队伍；只要还有一队未收获，就仍需完成收获。
        if (Boolean.TRUE.equals(tile.getGathering()) || pending.stream().anyMatch(march ->
                Boolean.TRUE.equals(march.getGathering()) && !Boolean.TRUE.equals(march.getGatherStopped()))) {
            return "有采集任务尚未收获，请先收获并下达部队回城命令后再放弃领地";
        }
        if (tile.getGatherHarvested() != null || pending.stream().anyMatch(march -> Boolean.TRUE.equals(march.getGatherStopped()))) {
            return "有部队已收获并留守，请先下达部队回城命令后再放弃领地";
        }
        if (!pending.isEmpty()) return "采集部队尚未召回，请先下达部队回城命令后再放弃领地";
        return blockerIds.isEmpty() ? null : "采集资源尚未结算，请稍后再放弃领地";
    }

    // ================================================================
    //  Helper methods
    // ================================================================

    /** Manhattan distance - 对应 JS dist(x1, y1, x2, y2) */
    private int manhattanDist(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    private int buildingLevel(Long playerId, String buildingType) {
        List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), buildingType);
        int sum = 0;
        for (Building b : buildings) {
            sum += b.getLevel() != null ? b.getLevel() : 0;
        }
        return sum;
    }

    private int getTechLevel(Long playerId, String techType) {
        List<Technology> techs = technologyRepository.findByPlayerIdAndType(playerId, techType);
        if (techs == null || techs.isEmpty()) return 0;
        return techs.get(0).getLevel() != null ? techs.get(0).getLevel() : 0;
    }
}
