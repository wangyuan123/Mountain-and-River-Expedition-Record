package com.wargame.service;

import com.wargame.util.GameClock;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CityService {
    @org.springframework.beans.factory.annotation.Autowired private CityCapacityService capacity;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TickService economy;
    @org.springframework.beans.factory.annotation.Autowired private GarrisonFoodService garrisonFood;
    public static final int SHIELD_PRICE = 200;
    private static final long SHIELD_DURATION_MS = 8L * 3600 * 1000;
    public static final long BUILD_DURATION_MS = 30 * 60 * 1000L;
    public static final Map<String, Integer> BUILD_COST = Map.of("food", 5000, "steel", 10000, "oil", 5000, "rare", 2000, "gold", 10000);
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final WildTileRepository wilds;
    private final ResourcesRepository resources;
    private final BuildingRepository buildings;
    private final CityStateRepository states;
    private final MarchRepository marches;
    private final CityScope scope;
    private final WorldTerrainService terrain;

    public CityService(PlayerRepository players, PlayerCityRepository cities, WildTileRepository wilds,
                       ResourcesRepository resources, BuildingRepository buildings, CityStateRepository states,
                       MarchRepository marches, CityScope scope, WorldTerrainService terrain) {
        this.players = players; this.cities = cities; this.wilds = wilds; this.resources = resources;
        this.buildings = buildings; this.states = states; this.marches = marches; this.scope = scope; this.terrain = terrain;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(Long playerId) {
        Player player = players.findById(playerId).orElseThrow();
        List<PlayerCity> owned = cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        int cap = MilitaryRankDef.getCityCap(player.getMilitaryRank());
        List<String> ids = owned.stream().map(c -> c.getId().toString()).toList();
        Set<String> incoming = new HashSet<>();
        if (!ids.isEmpty()) marches.findIncomingPlayerMarches(playerId, ids).forEach(m -> incoming.add(m.getTargetId()));
        List<Map<String, Object>> list = new ArrayList<>();
        for (PlayerCity city : owned) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", city.getId()); item.put("slot", city.getCitySlot()); item.put("name", city.getName());
            item.put("supportFoodPerHour", garrisonFood.supportUse(city));
            item.put("ownFoodPerHour", garrisonFood.ownUse(city) * garrisonFood.reduction(city));
            item.put("acceptGarrison", city.isAcceptGarrison());
            item.put("garrisonFoodUnmet", city.getGarrisonFoodUnmet());
            item.put("main", city.getCitySlot() == 0); item.put("current", city.getCitySlot() == scope.slot(playerId));
            item.put("x", city.getX()); item.put("y", city.getY()); item.put("readyAt", city.getReadyAt());
            item.put("surrenderOfferId", city.getSurrenderOfferId());
            item.put("surrenderRecipientId", city.getSurrenderRecipientId());
            item.put("surrenderFrozenAt", city.getSurrenderFrozenAt());
            item.put("surrenderTransferDeadlineAt", city.getSurrenderTransferDeadlineAt());
            item.put("frozen", city.isSurrenderFrozen(GameClock.now()));
            item.put("ready", city.getReadyAt() <= GameClock.now()); item.put("incoming", incoming.contains(city.getId().toString()));
            item.put("shieldUntil", states.findByPlayerIdAndCitySlot(playerId, city.getCitySlot())
                    .map(s -> Objects.requireNonNullElse(s.getShieldUntil(), 0L)).orElse(0L));
            item.put("coastal", terrain.coastal(city)); item.put("legacyNaval", city.isLegacyNaval());
            item.put("level", buildings.findByPlayerIdAndCitySlotAndType(playerId, city.getCitySlot(), "command")
                    .stream().mapToInt(b -> Objects.requireNonNullElse(b.getLevel(), 0)).max().orElse(1));
            list.add(item);
        }
        result.put("cities", list); result.put("count", owned.size()); result.put("cap", cap);
        result.put("rankName", MilitaryRankDef.getRankName(player.getMilitaryRank()));
        var next = MilitaryRankDef.nextCityRank(player.getMilitaryRank());
        result.put("nextRankName", next == null ? null : next.name());
        result.put("nextCap", next == null ? cap : MilitaryRankDef.getCityCap(next.tier()));
        result.put("buildCost", BUILD_COST); result.put("buildDurationMs", BUILD_DURATION_MS);
        result.put("sites", wilds.findByOccupiedBy(playerId).stream()
                .filter(this::suitable).map(w -> Map.of("id", w.getId(), "x", w.getX(), "y", w.getY(), "type", w.getType())).toList());
        return result;
    }

    /** 报价表和实际开盾共用的总额，客户端仅按选中数量查表。 */
    public static int shieldCost(int count) { return Math.multiplyExact(count, SHIELD_PRICE); }

    /** 校验全部城市后按去重数量扣钻；与开战共用账号锁，保证扣款、单城续期原子生效。 */
    @Transactional
    public Map<String, Object> activateShields(Long playerId, List<Long> cityIds) {
        players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        if (cityIds == null || cityIds.isEmpty()) throw new IllegalArgumentException("请选择需要开启护盾的城市");
        List<PlayerCity> selected = new LinkedHashSet<>(cityIds).stream()
                .map(id -> scope.requireOwned(playerId, id, true)).toList();
        long now = GameClock.now();
        if (selected.stream().anyMatch(c -> c.isSurrenderFrozen(now)))
            throw new IllegalArgumentException("归降移交中的城市只读");
        int cost = shieldCost(selected.size());
        Resources wallet = scope.wallet(playerId);
        int diamond = Objects.requireNonNullElse(wallet.getDiamond(), 0);
        if (diamond < cost) throw new IllegalArgumentException("钻石余额不足，开启护盾需要 " + cost + " 钻石");
        wallet.setDiamond(diamond - cost);
        resources.save(wallet);
        for (PlayerCity city : selected) {
            try (var local = scope.enter(city)) { economy.settleEconomyBeforeChange(playerId); }
            CityState status = states.findByPlayerIdAndCitySlot(playerId, city.getCitySlot()).orElseGet(() -> {
                CityState created = new CityState();
                created.setPlayerId(playerId);
                created.setCitySlot(city.getCitySlot());
                return created;
            });
            status.setShieldUntil(Math.max(now, Objects.requireNonNullElse(status.getShieldUntil(), 0L)) + SHIELD_DURATION_MS);
            states.save(status);
        }
        return Map.of("success", true, "count", selected.size(), "cost", cost,
                "remainingDiamond", diamond - cost, "cities", selected.stream().map(PlayerCity::getName).toList());
    }

    @Transactional
    public Map<String, Object> deactivateShield(Long playerId, Long cityId) {
        PlayerCity city = (cityId == null)
                ? cities.findByOwnerIdAndCitySlot(playerId, scope.slot(playerId))
                        .or(() -> cities.findByOwnerIdAndCitySlot(playerId, 1))
                        .orElseThrow(() -> new IllegalArgumentException("未找到主城"))
                : cities.findById(cityId).filter(c -> c.getOwnerId().equals(playerId))
                        .orElseThrow(() -> new IllegalArgumentException("无权操作此城市"));
        // 归降冻结约束在事务内校验；主动撤盾前先结清原护盾生效时段。
        players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        try (var selected = scope.enter(city)) {
            scope.assertWritable(playerId);
            economy.settleEconomyBeforeChange(playerId);
        }
        CityState status = states.findByPlayerIdAndCitySlot(playerId, city.getCitySlot()).orElse(null);
        if (status != null) {
            status.setShieldUntil(0L);
            states.save(status);
        }
        return Map.of("success", true, "cityId", city.getId(), "cityName", city.getName(), "message", "已主动解除本城免战护盾");
    }

    private boolean suitable(WildTile tile) {
        if (!Boolean.TRUE.equals(tile.getOccupied())) return false;
        if (Set.of("hill").contains(tile.getType())) return true;
        return WorldTerrainService.isIsland(terrain.current(), tile.getX(), tile.getY()) || "forest".equals(tile.getType());
    }

    @Transactional
    public PlayerCity found(Long playerId, Long wildId, String name) {
        if (wildId == null) throw new IllegalArgumentException("请选择建城地块");
        return foundAt(playerId, wildId, null, null, name);
    }

    @Transactional
    public Map<String,Object> site(Long playerId,int x,int y) {
        players.lockById(playerId).orElseThrow();
        WorldMap world=terrain.lockWorld(); String mask=terrain.ensure();
        // 地图空地允许内陆与海岛建城，预览与提交共用四格占地校验。
        String reason=terrain.siteReason(world.getId(),mask,x,y,null);
        if(reason.isEmpty()&&!capacity.hasRoom(playerId,null))reason="城市数量（含待移交预留）已达军衔上限";
        boolean coastal=WorldTerrainService.isIsland(mask,x,y) || WorldTerrainService.coastal(mask,x,y,2);
        return Map.of("x",x,"y",y,"span",2,"coastal",coastal,"cityType",WorldTerrainService.foundingTerrain(mask,x,y),"valid",reason.isEmpty(),"reason",reason,"buildCost",BUILD_COST,"buildDurationMs",BUILD_DURATION_MS);
    }

    @Transactional
    public PlayerCity foundAt(Long playerId, Long wildId, Integer x, Integer y, String name) {
        name = com.wargame.model.constants.DisplayNamePolicy.validate(name, 12, "城市名称");
        // 接管恢复主城也会写世界地图，统一先锁账号再锁地图，避免与建城互相等待。
        Player player = players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        WorldMap world=terrain.lockWorld(); String mask=terrain.ensure();
        List<Integer> owned = cities.findCurrentSlotsForUpdate(playerId);
        if (!capacity.hasRoom(playerId, null)) {
            var next = MilitaryRankDef.nextCityRank(player.getMilitaryRank());
            throw new IllegalArgumentException("城市数量已达上限" + (next == null ? "" : "，晋升至" + next.name() + "可拥有" + MilitaryRankDef.getCityCap(next.tier()) + "座城市"));
        }
        if (owned.isEmpty()) throw new IllegalArgumentException("主城尚未初始化，请重新登录");
        WildTile tile = null;
        if(wildId!=null){
            tile=wilds.lockById(wildId).orElseThrow(()->new IllegalArgumentException("地块不存在"));
            if(!world.getId().equals(tile.getWorldId())||!playerId.equals(tile.getOccupiedBy())||!suitable(tile))throw new IllegalArgumentException("只能在自己占领的丘陵、森林或海岛地块上建城");
            x=tile.getX();y=tile.getY();
            if(marches.existsByTargetIdAndTargetKindIn(wildId.toString(),List.of("wild","wild_gather")))throw new IllegalArgumentException("该地块仍有行军，请等待部队返城后建城");
        }
        if(x==null||y==null)throw new IllegalArgumentException("请选择建城坐标");
        String reason=terrain.siteReason(world.getId(),mask,x,y,wildId);
        if(!reason.isEmpty())throw new IllegalArgumentException(reason);
        economy.beforeResourceChange(playerId, scope.slot(playerId));
        Resources balance = resources.findCityForTreatment(playerId, scope.slot(playerId)).orElseThrow();
        for (var cost : BUILD_COST.entrySet()) if (amount(balance, cost.getKey()) < cost.getValue()) throw new IllegalArgumentException("建城资源不足");
        balance.setFood(balance.getFood() - BUILD_COST.get("food")); balance.setSteel(balance.getSteel() - BUILD_COST.get("steel"));
        balance.setOil(balance.getOil() - BUILD_COST.get("oil")); balance.setRare(balance.getRare() - BUILD_COST.get("rare")); balance.setGold(balance.getGold() - BUILD_COST.get("gold"));
        resources.save(balance);
        int slot = owned.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        long readyAt = GameClock.now() + BUILD_DURATION_MS;
        PlayerCity city = new PlayerCity();
        city.setOwnerId(playerId); city.setCitySlot(slot); city.setName(name); city.setWorldId(world.getId());
        city.setX(x); city.setY(y); city.setLevel(1); city.setReadyAt(readyAt); city.setLastTick(readyAt);
        city.setResources("{}"); city.setForts("{}"); city.setScoutedBy("[]"); city.setPrestige(0);
        cities.saveAndFlush(city);
        Resources stock = new Resources(); stock.setPlayerId(playerId); stock.setCitySlot(slot);
        stock.setFood(1000); stock.setSteel(1000); stock.setOil(1000); stock.setRare(1000); stock.setGold(1000); stock.setDiamond(0);
        resources.save(stock);
        for (String type : List.of("command", "house", "farm", "refinery")) {
            Building building = new Building(); building.setPlayerId(playerId); building.setCitySlot(slot); building.setType(type); building.setLevel(1); buildings.save(building);
        }
        CityState state = new CityState(); state.setPlayerId(playerId); state.setCitySlot(slot); state.setStatus("peace"); states.save(state);
        if(tile!=null) wilds.delete(tile);
        return city;
    }

    @Transactional
    public PlayerCity select(Long playerId, Long cityId) {
        if (cityId == null) throw new IllegalArgumentException("请选择城市");
        Player player = players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        PlayerCity city = scope.requireOwned(playerId, cityId, true);
        player.setActiveCityId(cityId); player.setPosX(city.getX()); player.setPosY(city.getY()); players.save(player);
        return city;
    }

    private long amount(Resources r, String key) {
        return Objects.requireNonNullElse(switch (key) { case "food" -> r.getFood(); case "steel" -> r.getSteel(); case "oil" -> r.getOil(); case "rare" -> r.getRare(); default -> r.getGold(); }, 0L);
    }
}
