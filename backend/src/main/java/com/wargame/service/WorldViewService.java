package com.wargame.service;

import com.wargame.util.PlayerNames;

import com.wargame.util.GameClock;
import com.wargame.model.constants.JapaneseOfficers;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Map read model: region queries and batched ownership, independent of world generation. */
@Service
@Transactional(readOnly = true)
public class WorldViewService {
    @org.springframework.beans.factory.annotation.Autowired private WarPowerProtectionService warPowerProtection;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;
    @org.springframework.beans.factory.annotation.Autowired private RadarService radar;
    @org.springframework.beans.factory.annotation.Autowired
    private CityStateRepository cityStates;
    @org.springframework.beans.factory.annotation.Autowired private GuildSupportService guildSupport;
    @org.springframework.beans.factory.annotation.Autowired private GuildRelationService guildRelations;
    @org.springframework.beans.factory.annotation.Autowired private IncomingIntelligenceService intelligence;
    private final WorldMapRepository worldMapRepository;
    private final PlayerCityRepository playerCityRepository;
    private final BanditRepository banditRepository;
    private final WildTileRepository wildTileRepository;
    private final PlayerRepository playerRepository;
    private final MarchRepository marchRepository;
    private final BattleSessionRepository battleSessionRepository;
    private final IncomingMarchRepository incomingMarchRepository;
    private final ArmyUnitRepository armyUnitRepository;

    public WorldViewService(WorldMapRepository worldMapRepository, PlayerCityRepository playerCityRepository, BanditRepository banditRepository, WildTileRepository wildTileRepository, PlayerRepository playerRepository, MarchRepository marchRepository, BattleSessionRepository battleSessionRepository, IncomingMarchRepository incomingMarchRepository, ArmyUnitRepository armyUnitRepository) {
        this.worldMapRepository = worldMapRepository;
        this.playerCityRepository = playerCityRepository;
        this.banditRepository = banditRepository;
        this.wildTileRepository = wildTileRepository;
        this.playerRepository = playerRepository;
        this.marchRepository = marchRepository;
        this.battleSessionRepository = battleSessionRepository;
        this.incomingMarchRepository = incomingMarchRepository;
        this.armyUnitRepository = armyUnitRepository;
    }

    private Map<String, Object> armyMapFor(Long playerId, int slot) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ArmyUnit unit : armyUnitRepository.findByPlayerIdAndCitySlot(playerId, slot)) out.put(unit.getType(), unit.getCount());
        return out;
    }

    public Map<String, Object> getWorld(Long playerId, int x, int y, int radius) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        return getWorld(player, x, y, radius);
    }

    public Map<String, Object> getWorld(Player player, int x, int y, int radius) {
        int worldSize = worldMapRepository.findFirstByOrderByIdAsc().map(w -> w.getSize() != null ? w.getSize() : WorldConfig.WORLD_SIZE).orElse(WorldConfig.WORLD_SIZE);
        if (x < 0 || y < 0 || x >= worldSize || y >= worldSize
                || radius < 0 || radius > worldSize) {
            throw new IllegalArgumentException("地图坐标或视野半径超出范围");
        }
        int minX = radius == 0 ? 0 : Math.max(0, x - radius);
        int maxX = radius == 0 ? worldSize - 1 : Math.min(worldSize - 1, x + radius);
        int minY = radius == 0 ? 0 : Math.max(0, y - radius);
        int maxY = radius == 0 ? worldSize - 1 : Math.min(worldSize - 1, y + radius);
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("view", Map.of("x", x, "y", y, "radius", radius, "loadedAt", System.currentTimeMillis()));

        // Position
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", player.getPosX() != null ? player.getPosX() : 0);
        pos.put("y", player.getPosY() != null ? player.getPosY() : 0);
        world.put("pos", pos);

        Map<String, Object> cityPos = new LinkedHashMap<>();
        cityPos.put("x", cityScope.economy(player.getId()).getCityPosX());
        cityPos.put("y", cityScope.economy(player.getId()).getCityPosY());
        world.put("cityPos", cityPos);

        // Find world
        WorldMap worldMap = worldMapRepository.findFirstByOrderByIdAsc().orElse(null);
        Long worldId = worldMap != null ? worldMap.getId() : null;

        // NPC 城市/要塞已取消；保留空数组兼容旧前端状态结构。
        world.put("npcCities", new ArrayList<>());

        // Player cities and legacy ownerless cities, which are handled as simulated NPCs.
        List<Map<String, Object>> playerCities = new ArrayList<>();
        List<Map<String, Object>> simulatedNpcCities = new ArrayList<>();
        if (worldId != null) {
            List<PlayerCity> entities = playerCityRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId, minX, maxX, minY, maxY);
            Map<String, Player> byCoordinates = new HashMap<>();
            Map<Long, Player> byId = new HashMap<>();
            playerRepository.findByCityPosXBetweenAndCityPosYBetween(minX, maxX, minY, maxY)
                    .forEach(owner -> {
                        byCoordinates.put(owner.getCityPosX() + "," + owner.getCityPosY(), owner);
                        byId.put(owner.getId(), owner);
                    });
            Set<Long> missingOwners = new HashSet<>();
            entities.stream().map(PlayerCity::getOwnerId).filter(Objects::nonNull)
                    .filter(id -> !byId.containsKey(id)).forEach(missingOwners::add);
            if (!missingOwners.isEmpty()) playerRepository.findAllById(missingOwners)
                    .forEach(owner -> byId.put(owner.getId(), owner));
            for (int i = 0; i < entities.size(); i++) {
                PlayerCity pc = entities.get(i);
                Map<String, Object> pcMap = new LinkedHashMap<>();
                pcMap.put("id", pc.getId());
                pcMap.put("readyAt", pc.getReadyAt());
                pcMap.put("mainCity", Integer.valueOf(0).equals(pc.getCitySlot()));
                pcMap.put("name", pc.getName());
                pcMap.put("x", pc.getX());
                pcMap.put("y", pc.getY());
                pcMap.put("level", pc.getLevel());
                pcMap.put("army", pc.getOwnerId() != null ? armyMapFor(pc.getOwnerId(), java.util.Objects.requireNonNullElse(pc.getCitySlot(), 0)) : Collections.emptyMap());
                pcMap.put("forts", JsonUtil.parseObjMap(pc.getForts()));
                pcMap.put("reward", JsonUtil.parseObjMap(pc.getResources()));
                pcMap.put("defeated", false);
                pcMap.put("prestige", pc.getPrestige() != null ? pc.getPrestige() : 0);
                pcMap.put("surrenderFrozen", pc.isSurrenderFrozen(GameClock.now()));
                pcMap.put("surrenderTransferDeadlineAt", pc.getSurrenderTransferDeadlineAt());

                // 坐标上的真实玩家优先于历史 owner_id；无真实 owner 的 PlayerCity 视为模拟 NPC。
                Long cityOwner = pc.getOwnerId();
                Player coordinateOwner = byCoordinates.get(pc.getX() + "," + pc.getY());
                if (coordinateOwner != null) {
                    cityOwner = coordinateOwner.getId();
                }
                Player owner = cityOwner != null ? byId.get(cityOwner) : null;
                if (owner != null && owner.deletionDue(System.currentTimeMillis())) continue;
                if (owner == null) {
                    cityOwner = null;
                    pcMap.put("simulatedNpc", true);
                    simulatedNpcCities.add(pcMap);
                    continue;
                }
                boolean selfCity = cityOwner.equals(player.getId());
                pcMap.put("ownerId", cityOwner);
                pcMap.put("guildmate", guildRelations.areGuildmates(player.getId(), cityOwner));
                pcMap.put("playerName", owner != null ? PlayerNames.commanderName(owner) : "");
                // 地图列表展开时展示游戏内统帅名与当前军衔；登录名仅作为旧数据兜底。
                pcMap.put("commanderName", owner.getDisplayName() == null || owner.getDisplayName().isBlank()
                        ? PlayerNames.commanderName(owner) : owner.getDisplayName());
                pcMap.put("militaryRank", owner.getMilitaryRank());
                pcMap.put("militaryRankName", MilitaryRankDef.getRankName(owner.getMilitaryRank()));
                pcMap.put("prestige", owner.getPrestige() != null ? owner.getPrestige() : 0);
                pcMap.put("level", 0); // 真实玩家等级仅个人档案可见，地图区不展示玩家等级
                long pcWarAt = 0L, pcWarEnd = 0L;
                if (!selfCity && owner != null && player.getId().equals(owner.getWarAgainstId())) {
                    pcWarAt = owner.getWarAt() != null ? owner.getWarAt() : 0L;
                    pcWarEnd = owner.getWarEndAt() != null ? owner.getWarEndAt() : 0L;
                }
                pcMap.put("selfCity", selfCity);
                pcMap.put("warAt", pcWarAt);
                pcMap.put("warEndAt", pcWarEnd);
                pcMap.put("canAccelerateWar", pcWarAt > GameClock.now()
                        && player.getId().equals(owner.getWarInitiatorId())
                        && Objects.equals(pc.getId(), owner.getWarTargetCityId())
                        && Objects.equals(cityScope.slot(player.getId()), owner.getWarCitySlot())
                        && !owner.isWarAccelerated());
                // coolAt 表示战后保护期 (仅当未处于战争状态时), 战争窗口用 warAt/warEndAt 表达
                pcMap.put("coolAt", pcWarAt == 0 ? pcWarEnd : 0L);
                // 列表与坐标地图共用新手保护规则，避免敌对关系绕过受保护城市的行动限制。
                long newbieUntil = com.wargame.model.constants.NewbieProtection.until(owner, GameClock.now());
                long itemUntil = cityStates.findByPlayerIdAndCitySlot(cityOwner, pc.getCitySlot())
                        .map(cs -> Objects.requireNonNullElse(cs.getShieldUntil(), 0L)).orElse(0L);
                pcMap.put("newbieProtectionUntil", newbieUntil);
                if (Math.max(newbieUntil, itemUntil) > GameClock.now()) pcMap.put("shieldUntil", Math.max(newbieUntil, itemUntil));
                playerCities.add(pcMap);
            }
        }
        Set<Long> powerOwners = new HashSet<>();
        playerCities.stream().filter(city -> !Boolean.TRUE.equals(city.get("selfCity")))
                .forEach(city -> powerOwners.add(((Number) city.get("ownerId")).longValue()));
        var powerEligibility = warPowerProtection.evaluateMany(player.getId(), powerOwners);
        playerCities.forEach(city -> {
            if (city.get("ownerId") instanceof Number owner && powerEligibility.containsKey(owner.longValue()))
                city.put("powerProtection", powerEligibility.get(owner.longValue()).toMap());
        });
        world.put("playerCities", playerCities);
        world.put("simulatedNpcCities", simulatedNpcCities);

        // Bandits
        List<Map<String, Object>> bandits = new ArrayList<>();
        if (worldId != null) {
            List<Bandit> entities = banditRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId, minX, maxX, minY, maxY);
            for (int i = 0; i < entities.size(); i++) {
                Bandit b = entities.get(i);
                // 保留数据库记录供定时迁移，但地图不展示已击败目标。
                if (Boolean.TRUE.equals(b.getDefeated())) continue;
                Map<String, Object> bMap = new LinkedHashMap<>();
                bMap.put("id", b.getId());
                bMap.put("name", WorldTerrainService.sea(worldMap.getTerrainData(), b.getX(), b.getY())
                        ? b.getName() : WorldConfig.landBanditName(b.getLevel()));
                bMap.put("x", b.getX());
                bMap.put("y", b.getY());
                bMap.put("level", b.getLevel());
                bMap.put("army", JsonUtil.parseObjMap(b.getArmy()));
                bMap.put("commanderName", b.getCommanderName() != null ? b.getCommanderName()
                        : JapaneseOfficers.getCommanderForLevel(b.getLevel() != null ? b.getLevel() : 1));
                bMap.put("defeated", b.getDefeated() != null && b.getDefeated());
                bMap.put("sea", worldMap.getTerrainData() != null
                        && WorldTerrainService.sea(worldMap.getTerrainData(), b.getX(), b.getY()));
                bandits.add(bMap);
            }
        }
        world.put("bandits", bandits);

        // Wild tiles
        List<Map<String, Object>> wildTiles = new ArrayList<>();
        if (worldId != null) {
            List<WildTile> entities = wildTileRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId, minX, maxX, minY, maxY);
            Map<Long, WildTile> visibleAndOwned = new LinkedHashMap<>();
            entities.stream().filter(tile -> !tile.isDormant())
                    .forEach(tile -> visibleAndOwned.put(tile.getId(), tile));
            wildTileRepository.findByOccupiedBy(player.getId()).stream()
                    .filter(tile -> worldId.equals(tile.getWorldId()))
                    .forEach(tile -> visibleAndOwned.put(tile.getId(), tile));
            entities = new ArrayList<>(visibleAndOwned.values());
            // 批量读取占领者公开资料，避免全图查询按野地逐条读取玩家。
            Set<Long> occupierIds = new HashSet<>();
            entities.stream().filter(tile -> Boolean.TRUE.equals(tile.getOccupied()))
                    .map(WildTile::getOccupiedBy).filter(Objects::nonNull).forEach(occupierIds::add);
            Map<Long, Player> occupiers = new HashMap<>();
            if (!occupierIds.isEmpty()) playerRepository.findAllById(occupierIds)
                    .forEach(owner -> occupiers.put(owner.getId(), owner));
            for (int i = 0; i < entities.size(); i++) {
                WildTile wt = entities.get(i);
                Map<String, Object> wtMap = new LinkedHashMap<>();
                wtMap.put("id", wt.getId());
                wtMap.put("type", wt.getType());
                wtMap.put("x", wt.getX());
                wtMap.put("y", wt.getY());
                wtMap.put("level", wt.getLevel());
                wtMap.put("scouted", wt.getScouted() != null && wt.getScouted());
                boolean isOwner = Boolean.TRUE.equals(wt.getOccupied()) && player.getId().equals(wt.getOccupiedBy());
                // 本人的驻军用于采集和撤回；其他目标的守军只能通过侦察报告获知。
                if (isOwner) wtMap.put("garrison", JsonUtil.parseObjMap(wt.getGarrison()));
                wtMap.put("occupied", isOwner);
                if (isOwner) wtMap.put("occupiedCitySlot", Objects.requireNonNullElse(wt.getOccupiedCitySlot(), 0));
                if (Boolean.TRUE.equals(wt.getOccupied()) && wt.getOccupiedBy() != null) {
                    Player owner = occupiers.get(wt.getOccupiedBy());
                    if (owner != null) {
                        wtMap.put("occupiedByName", owner.getDisplayName() == null || owner.getDisplayName().isBlank() ? PlayerNames.commanderName(owner) : owner.getDisplayName());
                        wtMap.put("occupiedByRank", owner.getMilitaryRank());
                        wtMap.put("occupiedByRankName", MilitaryRankDef.getRankName(owner.getMilitaryRank()));
                        wtMap.put("occupiedByPrestige", owner.getPrestige());
                    }
                }
                wtMap.put("totalRes", wt.getTotalRes() != null ? wt.getTotalRes() : 0);
                wtMap.put("mined", wt.getMined() != null ? wt.getMined() : 0);
                if (isOwner) {
                    wtMap.put("gathering", Boolean.TRUE.equals(wt.getGathering()));
                    wtMap.put("gatherStartAt", wt.getGatherStartAt() != null ? wt.getGatherStartAt() : 0L);
                    wtMap.put("gatherEndAt", wt.getGatherEndAt() != null ? wt.getGatherEndAt() : 0L);
                    wtMap.put("gatherLoad", wt.getGatherLoad() != null ? wt.getGatherLoad() : 0);
                    wtMap.put("gatherRes", wt.getGatherRes());
                    wtMap.put("gatherMode", "manual");
                    wtMap.put("gatherHarvested", wt.getGatherHarvested());
                }
                wildTiles.add(wtMap);
            }
        }
        world.put("wildTiles", wildTiles);

        // Marches
        List<Map<String, Object>> marches = new ArrayList<>();
        Long playerId = player.getId();
        if (playerId != null) {
            List<March> entities = marchRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
            long now = GameClock.now();
            for (March m : entities) {
                marches.add(toMarchMap(m, now));
            }
        }
        world.put("marches", marches);
        world.put("reinforcements", getReinforcements(playerId));

        world.put("incoming", getIncoming(player));
        world.put("regionalRadar", radar.view(player.getId(), 0));
        world.put("returningEnemyMarches", getReturningEnemyMarches(player));

        return world;
    }

    /** 来袭情报直接关联实际行军，避免再创建一份会重复结算战斗的 IncomingMarch。 */
    public List<Map<String, Object>> getIncoming(Player player) {
        Long playerId = player.getId();
        long now = GameClock.now();
        List<Map<String, Object>> incoming = new ArrayList<>();
        int radar = intelligence.radar(playerId);
        if (playerId != null) {
            List<IncomingMarch> entities = incomingMarchRepository.findByTargetPlayerId(playerId);
            for (IncomingMarch im : entities) {
                Map<String, Object> imMap = new LinkedHashMap<>();
                imMap.put("id", im.getId());
                imMap.put("fromName", im.getFromName());
                imMap.put("fromX", im.getFromX());
                imMap.put("fromY", im.getFromY());
                // 前端军情卡使用这组展示字段；来袭记录本身只有来源信息。
                imMap.put("attackerName", im.getFromName());
                imMap.put("sourcePlayer", im.getFromName());
                imMap.put("targetX", player.getCityPosX() != null ? player.getCityPosX() : 0);
                imMap.put("targetY", player.getCityPosY() != null ? player.getCityPosY() : 0);
                imMap.put("targetName", player.getCityName() != null && !player.getCityName().isEmpty()
                        ? player.getCityName() : "新城市");
                imMap.put("army", JsonUtil.parseObjMap(im.getArmy()));
                imMap.put("arriveAt", im.getArriveAt());
                imMap.put("action", im.getAction());
                imMap.put("arrived", false);
                incoming.add(intelligence.view(radar, imMap, null));
            }
        }

        List<PlayerCity> cities = playerCityRepository.findByOwnerId(playerId);
        if (!cities.isEmpty()) {
            List<String> cityIds = cities.stream().map(c -> String.valueOf(c.getId())).toList();
            List<March> incomingMarches = marchRepository.findIncomingPlayerMarches(playerId, cityIds);
            Set<String> activeCityIds = new HashSet<>();
            incomingMarches.stream().filter(march -> march.getBattleId() != null)
                    .forEach(march -> activeCityIds.add(march.getTargetId()));
            Map<String, Long> nextBattleIds = new HashMap<>();
            incomingMarches.stream()
                    .filter(march -> march.getBattleId() == null && !"scout".equals(march.getAction())
                            && now >= Objects.requireNonNullElse(march.getArriveAt(), Long.MAX_VALUE))
                    .forEach(march -> nextBattleIds.putIfAbsent(march.getTargetId(), march.getId()));
            for (March march : incomingMarches) {
                Player attacker = playerRepository.findById(march.getPlayerId()).orElse(null);
                Map<String, Object> info = new LinkedHashMap<>();
                // 与旧版 IncomingMarch 的数字 ID 分开，避免前端误去重。
                info.put("id", "march-" + march.getId());
                info.put("marchId", march.getId());
                info.put("fromName", attacker != null ? PlayerNames.commanderName(attacker) : "未知敌军");
                info.put("attackerName", info.get("fromName"));
                info.put("fromX", march.getFromX());
                info.put("fromY", march.getFromY());
                info.put("targetCityId", march.getTargetId());
                info.put("targetName", march.getTargetName());
                info.put("targetX", march.getTargetX());
                info.put("targetY", march.getTargetY());
                info.put("army", JsonUtil.parseObjMap(march.getArmy()));
                // 来袭地图与出征方共用权威起止时间，支持途中加速后的移动插值。
                info.put("startAt", march.getStartAt());
                info.put("arriveAt", march.getArriveAt());
                info.put("action", march.getAction());
                info.put("inBattle", march.getBattleId() != null);
                info.put("waitingForBattle", march.getBattleId() == null
                        && now >= Objects.requireNonNullElse(march.getArriveAt(), Long.MAX_VALUE)
                        && !"scout".equals(march.getAction())
                        && (activeCityIds.contains(march.getTargetId())
                        || !march.getId().equals(nextBattleIds.get(march.getTargetId()))));
                // 防守方可在抵达后直接进入同一战术会话，首次读取会由战斗接口补建会话。
                info.put("arrived", march.getBattleId() != null
                        || now >= Objects.requireNonNullElse(march.getArriveAt(), Long.MAX_VALUE));
                info.put("countermeasuresUsed", Boolean.TRUE.equals(march.getStratagemUsed()));
                incoming.add(intelligence.view(radar, info, march));
            }
        }
        // 野外急报按被截击行军的归属查询；切换城市和地图视野都不应隐藏本账号的遭遇。
        Map<String, March> outbound = new LinkedHashMap<>();
        for (March march : marchRepository.findByPlayerId(playerId)) {
            if (!Boolean.TRUE.equals(march.getReturning()) && march.getBattleId() == null
                    && ("scout".equals(march.getAction()) || BattleService.isOffensiveAction(march.getAction())
                    || (march.getAction() != null && march.getAction().startsWith("tactical")))
                    && "player".equals(march.getTargetKind())) {
                outbound.put(String.valueOf(march.getId()), march);
            }
        }
        if (!outbound.isEmpty()) {
            for (March interceptor : marchRepository.findActiveInterceptions(new ArrayList<>(outbound.keySet()))) {
                March intercepted = outbound.get(interceptor.getTargetId());
                if (playerId.equals(interceptor.getPlayerId()) || interceptor.getArriveAt() == null
                        || intercepted.getArriveAt() == null || interceptor.getArriveAt() > intercepted.getArriveAt()) continue;
                Player source = playerRepository.findById(interceptor.getPlayerId()).orElse(null);
                incoming.add(intelligence.fieldInterception(interceptor, intercepted, source));
            }
        }
        return incoming;
    }

    /**
     * 为守方展示从其城市撤退的敌军，只公开路线所需数据。
     * @param player 当前查看地图的玩家
     * @return 可用于地图渲染的敌军返程情报
     */
    public List<Map<String, Object>> getReturningEnemyMarches(Player player) {
        Long playerId = player.getId();
        List<PlayerCity> cities = playerCityRepository.findByOwnerId(playerId);
        if (cities.isEmpty()) return List.of();
        List<String> cityIds = cities.stream().map(city -> String.valueOf(city.getId())).toList();
        List<Map<String, Object>> returning = new ArrayList<>();
        long now = GameClock.now();
        for (March march : marchRepository.findReturningEnemyPlayerMarches(playerId, cityIds)) {
            if (march.getArriveAt() == null || march.getArriveAt() <= now) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", "return-march-" + march.getId());
            info.put("marchId", march.getId());
            info.put("targetKind", "player");
            info.put("targetCityId", march.getTargetId());
            info.put("countermeasuresUsed", Boolean.TRUE.equals(march.getReturnStratagemUsed()));
            info.put("fromName", playerRepository.findById(march.getPlayerId()).map(com.wargame.util.PlayerNames::commanderName).orElse("未知敌军"));
            info.put("targetName", march.getTargetName());
            info.put("fromX", march.getFromX());
            info.put("fromY", march.getFromY());
            info.put("targetX", march.getTargetX());
            info.put("targetY", march.getTargetY());
            info.put("startAt", march.getStartAt());
            info.put("arriveAt", march.getArriveAt());
            info.put("action", march.getAction());
            info.put("returning", true);
            returning.add(info);
        }
        return returning;
    }

    public List<Map<String, Object>> getReinforcements(Long playerId) {
        return guildSupport.received(playerId);
    }

    public Map<String, Object> toMarchMap(March m, long now) {
        Map<String, Object> mMap = new LinkedHashMap<>();
        mMap.put("id", m.getId());
        mMap.put("version", m.getVersion());
        mMap.put("outboundAccelerated", Boolean.TRUE.equals(m.getOutboundAccelerated()));
        mMap.put("returnAccelerated", Boolean.TRUE.equals(m.getReturnAccelerated()));
        mMap.put("stratagemUsed", Boolean.TRUE.equals(m.getStratagemUsed()));
        mMap.put("returnStratagemUsed", Boolean.TRUE.equals(m.getReturnStratagemUsed()));
        mMap.put("targetKind", m.getTargetKind());
        mMap.put("targetId", m.getTargetId());
        mMap.put("targetName", m.getTargetName());
        mMap.put("targetX", m.getTargetX());
        mMap.put("targetY", m.getTargetY());
        mMap.put("fromX", m.getFromX());
        mMap.put("fromY", m.getFromY());
        mMap.put("distance", m.getDistance());
        mMap.put("routeMode", m.getRouteMode());
        mMap.put("route", JsonUtil.parseTree(m.getRouteData()));
        mMap.put("action", m.getAction());
        mMap.put("battleMode", m.getBattleMode());
        mMap.put("conquerMode", Objects.requireNonNullElse(m.getConquerMode(), "withdraw"));
        mMap.put("army", JsonUtil.parseObjMap(m.getArmy()));
        mMap.put("unitEquipment", JsonUtil.parseIntMap(m.getUnitEquipmentSnapshot()));
        mMap.put("commanderId", m.getCommanderId());
        mMap.put("carryRes", JsonUtil.parseObjMap(m.getCarryRes()));
        mMap.put("startAt", m.getStartAt());
        mMap.put("arriveAt", m.getArriveAt());
        mMap.put("deployAt", m.getInterceptDeployAt());
        mMap.put("readyAt", m.getInterceptReadyAt());
        if (!Boolean.TRUE.equals(m.getReturning()) && m.getInterceptDeployAt() != null) {
            boolean used = "incoming_march".equals(m.getTargetKind())
                    ? marchRepository.findById(Long.valueOf(m.getTargetId())).map(March::getAmbushUsed).orElse(true)
                    : incomingMarchRepository.findById(Long.valueOf(m.getTargetId())).map(IncomingMarch::getAmbushUsed).orElse(true);
            boolean ambush = !used && m.getInterceptReadyAt() <= m.getArriveAt();
            mMap.put("interceptionPhase", now < m.getInterceptDeployAt() ? "deploying"
                    : ambush && now < m.getInterceptReadyAt() ? "preparing" : "waiting");
            mMap.put("interceptionOpening", ambush ? "ambush" : "urgent");
        }
        mMap.put("returning", Boolean.TRUE.equals(m.getReturning()));
        mMap.put("stationed", Boolean.TRUE.equals(m.getStationed()));
        mMap.put("displaced", m.isDisplaced());
        mMap.put("returnReason", m.getReturnReason());
        mMap.put("battleWon", Boolean.TRUE.equals(m.getBattleWon()));
        mMap.put("win", Boolean.TRUE.equals(m.getBattleWon()));
        mMap.put("gathering", Boolean.TRUE.equals(m.getGathering()));
        mMap.put("gatherMode", "manual");
        mMap.put("gatherAfterAction", "retain");
        mMap.put("gatherAfterActionResult", m.getGatherAfterActionResult());
        mMap.put("gatherAfterActionReason", m.getGatherAfterActionReason());
        mMap.put("gatherStartAt", m.getGatherStartAt());
        mMap.put("gatherStopped", Boolean.TRUE.equals(m.getGatherStopped()));
        mMap.put("gatherEndAt", m.getGatherEndAt());
        mMap.put("gatherAmount", m.getGatherAmount());
        mMap.put("gatherRes", m.getGatherRes());
        mMap.put("battleId", m.getBattleId());
        mMap.put("inBattle", m.getBattleId() != null);
        boolean arrivedAttack = "player".equals(m.getTargetKind()) && !Boolean.TRUE.equals(m.getReturning())
                && BattleService.isOffensiveAction(m.getAction())
                && m.getBattleId() == null && now >= Objects.requireNonNullElse(m.getArriveAt(), Long.MAX_VALUE);
        if (arrivedAttack) {
            List<Long> waiting = marchRepository.findWaitingPlayerCityAttackIds(m.getTargetId(), now,
                    org.springframework.data.domain.PageRequest.of(0, 1));
            mMap.put("waitingForBattle", battleSessionRepository.existsByTargetKindAndTargetId("player", m.getTargetId())
                    || (!waiting.isEmpty() && !m.getId().equals(waiting.get(0))));
        }
        mMap.put("originName", m.getOriginName());
        mMap.put("originX", m.getOriginX());
        mMap.put("originY", m.getOriginY());

        long start = m.getStartAt() != null ? m.getStartAt() : now;
        long arrive = m.getArriveAt() != null ? m.getArriveAt() : now;
        if (Boolean.TRUE.equals(m.getGathering())) {
            long gatherEnd = m.getGatherEndAt() != null ? m.getGatherEndAt() : now;
            long gatherStart = m.getGatherStartAt() != null ? m.getGatherStartAt() : arrive;
            mMap.put("progress", calcProgress(gatherStart, gatherEnd, now));
        } else {
            mMap.put("progress", calcProgress(start, arrive, now));
        }
        return mMap;
    }

    private int calcProgress(long start, long end, long now) {
        if (end <= start) return 100;
        if (now <= start) return 0;
        if (now >= end) return 100;
        return (int) Math.min(100, Math.max(0, (now - start) * 100 / (end - start)));
    }

}
