package com.wargame.service;

import com.wargame.model.constants.WorldConfig;
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

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;
    private final WorldMapRepository worldMapRepository;
    private final NpcCityRepository npcCityRepository;
    private final PlayerCityRepository playerCityRepository;
    private final BanditRepository banditRepository;
    private final WildTileRepository wildTileRepository;
    private final PlayerRepository playerRepository;
    private final MarchRepository marchRepository;
    private final BattleSessionRepository battleSessionRepository;
    private final IncomingMarchRepository incomingMarchRepository;
    private final ArmyUnitRepository armyUnitRepository;

    public WorldViewService(WorldMapRepository worldMapRepository, NpcCityRepository npcCityRepository, PlayerCityRepository playerCityRepository, BanditRepository banditRepository, WildTileRepository wildTileRepository, PlayerRepository playerRepository, MarchRepository marchRepository, BattleSessionRepository battleSessionRepository, IncomingMarchRepository incomingMarchRepository, ArmyUnitRepository armyUnitRepository) {
        this.worldMapRepository = worldMapRepository;
        this.npcCityRepository = npcCityRepository;
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
        if (x < 0 || y < 0 || x >= WorldConfig.SIZE || y >= WorldConfig.SIZE
                || radius < 0 || radius > WorldConfig.SIZE) {
            throw new IllegalArgumentException("地图坐标或视野半径超出范围");
        }
        int minX = radius == 0 ? 0 : Math.max(0, x - radius);
        int maxX = radius == 0 ? WorldConfig.SIZE - 1 : Math.min(WorldConfig.SIZE - 1, x + radius);
        int minY = radius == 0 ? 0 : Math.max(0, y - radius);
        int maxY = radius == 0 ? WorldConfig.SIZE - 1 : Math.min(WorldConfig.SIZE - 1, y + radius);
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

        // NPC cities
        List<Map<String, Object>> npcCities = new ArrayList<>();
        if (worldId != null) {
            List<NpcCity> entities = npcCityRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId, minX, maxX, minY, maxY);
            for (int i = 0; i < entities.size(); i++) {
                NpcCity nc = entities.get(i);
                Map<String, Object> ncMap = new LinkedHashMap<>();
                ncMap.put("id", nc.getId());
                ncMap.put("name", nc.getName());
                ncMap.put("x", nc.getX());
                ncMap.put("y", nc.getY());
                ncMap.put("level", nc.getLevel());
                ncMap.put("army", JsonUtil.parseObjMap(nc.getArmy()));
                ncMap.put("forts", JsonUtil.parseObjMap(nc.getForts()));
                ncMap.put("reward", JsonUtil.parseObjMap(nc.getResources()));
                ncMap.put("defeated", nc.getDefeated() != null && nc.getDefeated());
                npcCities.add(ncMap);
            }
        }
        world.put("npcCities", npcCities);

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
                pcMap.put("playerName", owner != null ? owner.getUsername() : "");
                pcMap.put("level", 0); // 真实玩家等级仅个人档案可见，地图区不展示玩家等级
                long pcWarAt = 0L, pcWarEnd = 0L;
                if (!selfCity && owner != null && player.getId().equals(owner.getWarAgainstId())) {
                    pcWarAt = owner.getWarAt() != null ? owner.getWarAt() : 0L;
                    pcWarEnd = owner.getWarEndAt() != null ? owner.getWarEndAt() : 0L;
                }
                pcMap.put("selfCity", selfCity);
                pcMap.put("warAt", pcWarAt);
                pcMap.put("warEndAt", pcWarEnd);
                // coolAt 表示战后保护期 (仅当未处于战争状态时), 战争窗口用 warAt/warEndAt 表达
                pcMap.put("coolAt", pcWarAt == 0 ? pcWarEnd : 0L);
                playerCities.add(pcMap);
            }
        }
        world.put("playerCities", playerCities);
        world.put("simulatedNpcCities", simulatedNpcCities);

        // Bandits
        List<Map<String, Object>> bandits = new ArrayList<>();
        if (worldId != null) {
            List<Bandit> entities = banditRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId, minX, maxX, minY, maxY);
            for (int i = 0; i < entities.size(); i++) {
                Bandit b = entities.get(i);
                Map<String, Object> bMap = new LinkedHashMap<>();
                bMap.put("id", b.getId());
                bMap.put("name", b.getName());
                bMap.put("x", b.getX());
                bMap.put("y", b.getY());
                bMap.put("level", b.getLevel());
                bMap.put("army", JsonUtil.parseObjMap(b.getArmy()));
                bMap.put("defeated", b.getDefeated() != null && b.getDefeated());
                bandits.add(bMap);
            }
        }
        world.put("bandits", bandits);

        // Wild tiles
        List<Map<String, Object>> wildTiles = new ArrayList<>();
        if (worldId != null) {
            List<WildTile> entities = wildTileRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(worldId, minX, maxX, minY, maxY);
            Map<Long, WildTile> visibleAndOwned = new LinkedHashMap<>();
            entities.forEach(tile -> visibleAndOwned.put(tile.getId(), tile));
            wildTileRepository.findByOccupiedBy(player.getId()).stream()
                    .filter(tile -> worldId.equals(tile.getWorldId()))
                    .forEach(tile -> visibleAndOwned.put(tile.getId(), tile));
            entities = new ArrayList<>(visibleAndOwned.values());
            for (int i = 0; i < entities.size(); i++) {
                WildTile wt = entities.get(i);
                Map<String, Object> wtMap = new LinkedHashMap<>();
                wtMap.put("id", wt.getId());
                wtMap.put("type", wt.getType());
                wtMap.put("x", wt.getX());
                wtMap.put("y", wt.getY());
                wtMap.put("level", wt.getLevel());
                wtMap.put("garrison", JsonUtil.parseObjMap(wt.getGarrison()));
                wtMap.put("scouted", wt.getScouted() != null && wt.getScouted());
                boolean isOwner = Boolean.TRUE.equals(wt.getOccupied()) && player.getId().equals(wt.getOccupiedBy());
                wtMap.put("occupied", isOwner);
                wtMap.put("totalRes", wt.getTotalRes() != null ? wt.getTotalRes() : 0);
                wtMap.put("mined", wt.getMined() != null ? wt.getMined() : 0);
                if (isOwner) {
                    wtMap.put("gathering", Boolean.TRUE.equals(wt.getGathering()));
                    wtMap.put("gatherStartAt", wt.getGatherStartAt() != null ? wt.getGatherStartAt() : 0L);
                    wtMap.put("gatherEndAt", wt.getGatherEndAt() != null ? wt.getGatherEndAt() : 0L);
                    wtMap.put("gatherLoad", wt.getGatherLoad() != null ? wt.getGatherLoad() : 0);
                    wtMap.put("gatherRes", wt.getGatherRes());
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
            long now = System.currentTimeMillis();
            for (March m : entities) {
                marches.add(toMarchMap(m, now));
            }
        }
        world.put("marches", marches);

        world.put("incoming", getIncoming(player));

        return world;
    }

    /** 来袭情报直接关联实际行军，避免再创建一份会重复结算战斗的 IncomingMarch。 */
    public List<Map<String, Object>> getIncoming(Player player) {
        Long playerId = player.getId();
        long now = System.currentTimeMillis();
        List<Map<String, Object>> incoming = new ArrayList<>();
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
                incoming.add(imMap);
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
                info.put("fromName", attacker != null ? attacker.getUsername() : "未知敌军");
                info.put("attackerName", info.get("fromName"));
                info.put("fromX", march.getFromX());
                info.put("fromY", march.getFromY());
                info.put("targetCityId", march.getTargetId());
                info.put("targetName", march.getTargetName());
                info.put("targetX", march.getTargetX());
                info.put("targetY", march.getTargetY());
                info.put("army", JsonUtil.parseObjMap(march.getArmy()));
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
                incoming.add(info);
            }
        }
        return incoming;
    }

    public Map<String, Object> toMarchMap(March m, long now) {
        Map<String, Object> mMap = new LinkedHashMap<>();
        mMap.put("id", m.getId());
        mMap.put("targetKind", m.getTargetKind());
        mMap.put("targetIdx", m.getTargetIdx());
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
        mMap.put("army", JsonUtil.parseObjMap(m.getArmy()));
        mMap.put("commanderId", m.getCommanderId());
        mMap.put("carryRes", JsonUtil.parseObjMap(m.getCarryRes()));
        mMap.put("startAt", m.getStartAt());
        mMap.put("arriveAt", m.getArriveAt());
        mMap.put("returning", Boolean.TRUE.equals(m.getReturning()));
        mMap.put("gathering", Boolean.TRUE.equals(m.getGathering()));
        mMap.put("gatherEndAt", m.getGatherEndAt());
        mMap.put("gatherAmount", m.getGatherAmount());
        mMap.put("gatherRes", m.getGatherRes());
        mMap.put("battleId", m.getBattleId());
        mMap.put("inBattle", m.getBattleId() != null);
        boolean arrivedAttack = "player".equals(m.getTargetKind()) && !Boolean.TRUE.equals(m.getReturning())
                && ("conquer".equals(m.getAction()) || "plunder".equals(m.getAction()))
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
            mMap.put("progress", calcProgress(start, gatherEnd, now));
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
