package com.wargame.service;

import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CityService {
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
            item.put("main", city.getCitySlot() == 0); item.put("current", city.getCitySlot() == scope.slot(playerId));
            item.put("x", city.getX()); item.put("y", city.getY()); item.put("readyAt", city.getReadyAt());
            item.put("ready", city.getReadyAt() <= System.currentTimeMillis()); item.put("incoming", incoming.contains(city.getId().toString()));
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

    private boolean suitable(WildTile tile) {
        return Boolean.TRUE.equals(tile.getOccupied()) && Set.of("hill").contains(tile.getType());
    }

    @Transactional
    public PlayerCity found(Long playerId, Long wildId, String name) {
        if (wildId == null) throw new IllegalArgumentException("请选择建城地块");
        return foundAt(playerId, wildId, null, null, name);
    }

    @Transactional
    public Map<String,Object> site(Long playerId,int x,int y) {
        WorldMap world=terrain.lockWorld(); String mask=terrain.ensure();
        // Coordinate founding on empty land requires coastal 2x2 land.
        String reason=terrain.siteReason(world.getId(),mask,x,y,null,true);
        var player=players.findById(playerId).orElseThrow();
        if(reason.isEmpty()&&cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId).size()>=MilitaryRankDef.getCityCap(player.getMilitaryRank()))reason="城市数量已达军衔上限";
        boolean coastal=WorldTerrainService.coastal(mask,x,y,2);
        return Map.of("x",x,"y",y,"span",2,"coastal",coastal,"cityType",WorldTerrainService.foundingTerrain(mask,x,y),"valid",reason.isEmpty(),"reason",reason,"buildCost",BUILD_COST,"buildDurationMs",BUILD_DURATION_MS);
    }

    @Transactional
    public PlayerCity foundAt(Long playerId, Long wildId, Integer x, Integer y, String name) {
        WorldMap world=terrain.lockWorld(); String mask=terrain.ensure();
        if (name == null || !name.matches("[\\p{IsHan}A-Za-z0-9_]{1,12}")) throw new IllegalArgumentException("城市名仅限中英文、数字或下划线，最多12字");
        Player player = players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        List<PlayerCity> owned = cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId);
        int cap = MilitaryRankDef.getCityCap(player.getMilitaryRank());
        if (owned.size() >= cap) {
            var next = MilitaryRankDef.nextCityRank(player.getMilitaryRank());
            throw new IllegalArgumentException("城市数量已达上限" + (next == null ? "" : "，晋升至" + next.name() + "可拥有" + MilitaryRankDef.getCityCap(next.tier()) + "座城市"));
        }
        if (owned.isEmpty()) throw new IllegalArgumentException("主城尚未初始化，请重新登录");
        WildTile tile = null;
        if(wildId!=null){
            tile=wilds.lockById(wildId).orElseThrow(()->new IllegalArgumentException("地块不存在"));
            if(!world.getId().equals(tile.getWorldId())||!playerId.equals(tile.getOccupiedBy())||!suitable(tile))throw new IllegalArgumentException("只能在自己占领的丘陵地块上建山城");
            x=tile.getX();y=tile.getY();
            if(marches.existsByTargetIdAndTargetKindIn(wildId.toString(),List.of("wild","wild_gather")))throw new IllegalArgumentException("该地块仍有行军，请等待部队返城后建城");
        }
        if(x==null||y==null)throw new IllegalArgumentException("请选择建城坐标");
        String reason=terrain.siteReason(world.getId(),mask,x,y,wildId,wildId==null);
        if(!reason.isEmpty())throw new IllegalArgumentException(reason);
        Resources balance = resources.findCityForTreatment(playerId, scope.slot(playerId)).orElseThrow();
        for (var cost : BUILD_COST.entrySet()) if (amount(balance, cost.getKey()) < cost.getValue()) throw new IllegalArgumentException("建城资源不足");
        balance.setFood(balance.getFood() - BUILD_COST.get("food")); balance.setSteel(balance.getSteel() - BUILD_COST.get("steel"));
        balance.setOil(balance.getOil() - BUILD_COST.get("oil")); balance.setRare(balance.getRare() - BUILD_COST.get("rare")); balance.setGold(balance.getGold() - BUILD_COST.get("gold"));
        resources.save(balance);
        int slot = owned.stream().mapToInt(PlayerCity::getCitySlot).max().orElse(0) + 1;
        long readyAt = System.currentTimeMillis() + BUILD_DURATION_MS;
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

    private int amount(Resources r, String key) {
        return Objects.requireNonNullElse(switch (key) { case "food" -> r.getFood(); case "steel" -> r.getSteel(); case "oil" -> r.getOil(); case "rare" -> r.getRare(); default -> r.getGold(); }, 0);
    }
}
