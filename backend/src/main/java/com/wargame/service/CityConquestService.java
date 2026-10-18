package com.wargame.service;

import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** 玩家城接管及守城指挥官俘虏；与战斗结算共用事务，不复制军官或城市资产。 */
@Service
@RequiredArgsConstructor
@Transactional
public class CityConquestService {
    private final EntityManager em;
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final OfficerRepository officers;
    private final OfficerEquipmentRepository equipment;
    private final ResourcesRepository resources;
    private final MarketOrderRepository marketOrders;
    private final WildTileRepository wilds;
    private final WorldTerrainService terrain;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private MarchService marchService;

    private static final List<Class<? extends CityOwnedEntity>> CITY_ROWS = List.of(
            Building.class, Resources.class, Construction.class, ArmyUnit.class, Fortification.class,
            Officer.class, Academy.class, ArmyProductionQueue.class, WoundedUnit.class, PrisonerUnit.class,
            CityState.class, March.class, UnitEquipmentOrder.class, TechResearchQueue.class);

    /** 在结算后的民心与当前军衔名额上判断接管资格；建设中的城市也占名额。 */
    public String unavailableReason(Long attackerId, PlayerCity city) {
        Player attacker = players.findById(attackerId).orElseThrow();
        if (attackerId.equals(city.getOwnerId())) return "目标已属于我方";
        if (city.getCitySlot() == null) return "目标不是可接管的玩家城市";
        CityEconomy economy = city.getCitySlot() == 0 ? players.findById(city.getOwnerId()).orElseThrow() : city;
        if (!Integer.valueOf(0).equals(economy.getMorale())) return "敌方城市民心尚未降至0";
        if (cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(attackerId).size()
                >= MilitaryRankDef.getCityCap(attacker.getMilitaryRank())) return "城市数量已达当前军衔上限";
        return "";
    }

    /** 忠诚为0的真实守城指挥官原地转籍，装备、技能、经验和培养数据全部保留。 */
    public Officer capture(Long attackerId, int destinationSlot, Long defenderId, Officer commander) {
        if (commander == null || commander.getId() == null || !defenderId.equals(commander.getPlayerId())) return null;
        em.refresh(commander);
        if (!Integer.valueOf(0).equals(commander.getLoyalty())) return null;
        List<OfficerEquipment> worn = equipment.findByPlayerIdAndOfficerId(defenderId, commander.getId());
        for (OfficerEquipment item : worn) item.setPlayerId(attackerId);
        commander.setPlayerId(attackerId);
        commander.setCitySlot(destinationSlot);
        // 进入参谋部待命，不能覆盖我方现任指挥官；忠诚仍为0，可通过赏赐培养。
        commander.setRole("idle");
        officers.save(commander);
        em.createQuery("select m from March m where m.playerId = :player and m.commanderId = :officer", March.class)
                .setParameter("player", defenderId).setParameter("officer", commander.getId())
                .getResultList().forEach(m -> m.setCommanderId(null));
        return commander;
    }

    /**
     * 将建筑、建设队列及城内资源转入新槽位，销毁原城军事资产，并修复原玩家主城。
     * 调用方须锁定攻守双方玩家，并已完成伤兵、声望与民心结算和指挥官俘虏。
     */
    public int takeOver(Long attackerId, PlayerCity city, long now) {
        String reason = unavailableReason(attackerId, city);
        if (!reason.isEmpty()) throw new IllegalStateException(reason);
        Long defenderId = city.getOwnerId();
        int oldSlot = city.getCitySlot();
        Player defender = players.findById(defenderId).orElseThrow();
        int newSlot = cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(attackerId).stream()
                .mapToInt(PlayerCity::getCitySlot).max().orElse(0) + 1;
        Resources stock = resources.findByPlayerIdAndCitySlot(defenderId, oldSlot).orElseThrow();
        // 钻石是账号共享钱包，不随主城失守转移；普通资源留在接管城内，不再重复装入行军。
        int diamonds = oldSlot == 0 ? Objects.requireNonNullElse(stock.getDiamond(), 0) : 0;
        stock.setDiamond(0);
        closeMarketOrders(defenderId, oldSlot, stock);
        if (oldSlot == 0) copyEconomy(defender, city);
        for (Building building : rows(Building.class, defenderId, oldSlot)) {
            building.setPlayerId(attackerId); building.setCitySlot(newSlot);
        }
        for (Construction job : rows(Construction.class, defenderId, oldSlot)) {
            job.setPlayerId(attackerId); job.setCitySlot(newSlot);
        }
        stock.setPlayerId(attackerId); stock.setCitySlot(newSlot);
        for (Officer officer : rows(Officer.class, defenderId, oldSlot)) {
            equipment.deleteAll(equipment.findByPlayerIdAndOfficerId(defenderId, officer.getId()));
        }
        for (March march : rows(March.class, defenderId, oldSlot)) {
            em.createQuery("select s from BattleSession s where s.marchId = :march", BattleSession.class)
                    .setParameter("march", march.getId()).getResultList().forEach(em::remove);
        }
        // 删除队列和在途军队，避免失守后旧城军官、伤兵或招募任务在新城重新出现。
        for (Class<? extends CityOwnedEntity> type : CITY_ROWS) {
            if (type == Building.class || type == Resources.class || type == Construction.class) continue;
            rows(type, defenderId, oldSlot).forEach(em::remove);
        }
        updateGarrisons(defenderId, oldSlot, null);
        // 领地随城市转交；原城其他来源的幸存驻军先撤回，不转为新城主的兵力。
        for (WildTile tile : wilds.findByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(defenderId, oldSlot)) {
            marchService.evacuateWildGarrison(defenderId, tile, now);
            tile.setOccupiedBy(attackerId);
            tile.setOccupiedCitySlot(newSlot);
        }
        city.setOwnerId(attackerId); city.setCitySlot(newSlot);
        city.setArmy("{}"); city.setForts("{}"); city.setResources("{}");
        city.setWarAt(0L); city.setWarEndAt(0L); city.setScoutedBy("[]");
        city.setReadyAt(Math.min(now, city.getReadyAt())); city.setLastTick(now);
        em.flush();
        CityState peace = new CityState(); peace.setPlayerId(attackerId); peace.setCitySlot(newSlot); peace.setStatus("peace"); em.persist(peace);
        if (oldSlot == 0) restoreMainCity(defender, diamonds, now);
        else if (Objects.equals(defender.getActiveCityId(), city.getId())) {
            PlayerCity main = cities.findByOwnerIdAndCitySlot(defenderId, 0).orElseThrow();
            defender.setActiveCityId(main.getId()); defender.setPosX(main.getX()); defender.setPosY(main.getY());
        }
        return newSlot;
    }

    /**
     * Peaceful transfer used by accepted surrender offers. It keeps every city-owned
     * row and worn officer item, while the former owner receives the normal main-city
     * recovery path when the transferred city was their main city.
     */
    public int peacefulTransfer(Long recipientId, PlayerCity city, long now) {
        if (city == null || city.getOwnerId() == null || city.getCitySlot() == null) {
            throw new IllegalStateException("移交目标城市不存在");
        }
        Long formerOwnerId = city.getOwnerId();
        if (formerOwnerId.equals(recipientId)) throw new IllegalStateException("城市已经属于接收方");
        Player formerOwner = players.findById(formerOwnerId).orElseThrow();
        Player recipient = players.findById(recipientId).orElseThrow();
        int oldSlot = city.getCitySlot();
        int newSlot = cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(recipientId).stream()
                .mapToInt(PlayerCity::getCitySlot).max().orElse(0) + 1;

        Resources stock = resources.findByPlayerIdAndCitySlot(formerOwnerId, oldSlot).orElseThrow();
        int diamonds = oldSlot == 0 ? Objects.requireNonNullElse(stock.getDiamond(), 0) : 0;
        // Market orders are forbidden at acceptance; keep this defensive cleanup for old data.
        closeMarketOrders(formerOwnerId, oldSlot, stock);
        if (oldSlot == 0) copyEconomy(formerOwner, city);

        // 冻结期间城市时钟不推进；变更归属前按实际冻结时长顺延全部定时队列。
        shiftFrozenQueues(formerOwnerId, oldSlot, Math.max(0L, now - Objects.requireNonNullElse(city.getSurrenderFrozenAt(), now)));

        // Equipment is keyed by officer ID rather than city slot. Capture the old
        // officer rows before changing their owner, then move their worn items with them.
        List<Officer> transferredOfficers = rows(Officer.class, formerOwnerId, oldSlot);
        for (Officer officer : transferredOfficers) {
            equipment.findByPlayerIdAndOfficerId(formerOwnerId, officer.getId())
                    .forEach(item -> item.setPlayerId(recipientId));
        }

        // 和战斗接管一样随城转交领地；撤军先创建原城市返程，再随城迁移该城编队。
        for (WildTile tile : wilds.findByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(formerOwnerId, oldSlot)) {
            marchService.evacuateWildGarrison(formerOwnerId, tile, now);
            tile.setOccupiedBy(recipientId);
            tile.setOccupiedCitySlot(newSlot);
        }

        for (Class<? extends CityOwnedEntity> type : CITY_ROWS) {
            for (CityOwnedEntity row : rows(type, formerOwnerId, oldSlot)) {
                setOwner(row, recipientId);
                row.setCitySlot(newSlot);
            }
        }
        stock.setPlayerId(recipientId);
        stock.setCitySlot(newSlot);
        stock.setDiamond(0);
        resources.save(stock);

        city.setOwnerId(recipientId);
        city.setCitySlot(newSlot);
        city.setSurrenderOfferId(null);
        city.setSurrenderRecipientId(null);
        city.setSurrenderFrozenAt(null);
        city.setSurrenderTransferDeadlineAt(null);
        city.setWarAt(0L); city.setWarEndAt(0L); city.setScoutedBy("[]"); city.setLastTick(now);
        em.flush();
        if (oldSlot == 0) restoreMainCity(formerOwner, diamonds, now);
        else if (Objects.equals(formerOwner.getActiveCityId(), city.getId())) {
            PlayerCity main = cities.findByOwnerIdAndCitySlot(formerOwnerId, 0).orElseThrow();
            formerOwner.setActiveCityId(main.getId()); formerOwner.setPosX(main.getX()); formerOwner.setPosY(main.getY());
        }
        players.save(formerOwner); players.save(recipient);
        return newSlot;
    }

    private void setOwner(CityOwnedEntity row, Long playerId) {
        try {
            row.getClass().getMethod("setPlayerId", Long.class).invoke(row, playerId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("城市资产类型不支持移交: " + row.getClass().getSimpleName(), e);
        }
    }

    /** 将冻结期间未结算的城市队列整体顺延，避免移交后补算冻结收益。 */
    private void shiftFrozenQueues(Long playerId, int slot, long pausedMs) {
        if (pausedMs <= 0) return;
        for (Construction q : rows(Construction.class, playerId, slot)) {
            q.setStartAt(shift(q.getStartAt(), pausedMs)); q.setFinishAt(shift(q.getFinishAt(), pausedMs));
        }
        for (ArmyProductionQueue q : rows(ArmyProductionQueue.class, playerId, slot)) {
            q.setStartedAt(shift(q.getStartedAt(), pausedMs)); q.setFinishesAt(shift(q.getFinishesAt(), pausedMs));
        }
        for (TechResearchQueue q : rows(TechResearchQueue.class, playerId, slot)) {
            q.setStartedAt(shift(q.getStartedAt(), pausedMs)); q.setFinishesAt(shift(q.getFinishesAt(), pausedMs));
        }
        for (UnitEquipmentOrder q : rows(UnitEquipmentOrder.class, playerId, slot)) {
            q.setStartAt(shift(q.getStartAt(), pausedMs)); q.setEndAt(shift(q.getEndAt(), pausedMs));
        }
    }

    private Long shift(Long timestamp, long pausedMs) {
        return timestamp == null ? null : timestamp + pausedMs;
    }

    /** 主城失守时优先提升现有分城；无城玩家只获得基础城，不重置账号、科技或道具。 */
    private void restoreMainCity(Player defender, int diamonds, long now) {
        Long playerId = defender.getId();
        List<PlayerCity> remaining = cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId);
        PlayerCity main;
        if (remaining.isEmpty()) {
            WorldMap world = terrain.lockWorld(); String mask = terrain.ensure();
            Set<String> occupied = terrain.occupiedCoordinates(world.getId());
            int[] position = freeCitySite(mask, occupied);
            main = new PlayerCity(); main.setOwnerId(playerId); main.setCitySlot(0); main.setWorldId(world.getId());
            main.setName("重建主城"); main.setX(position[0]); main.setY(position[1]); main.setLevel(1);
            main.setLastTick(now); main.setCivilianPopulation(150); main.setResources("{}"); main.setForts("{}"); main.setScoutedBy("[]");
            em.persist(main);
            Resources stock = new Resources(); stock.setPlayerId(playerId); stock.setCitySlot(0);
            stock.setFood(2000); stock.setSteel(3000); stock.setOil(1000); stock.setRare(200); stock.setGold(1000); stock.setDiamond(diamonds); em.persist(stock);
            for (String type : List.of("command", "house", "farm", "refinery")) {
                Building b = new Building(); b.setPlayerId(playerId); b.setType(type); b.setLevel(1); em.persist(b);
            }
            CityState peace = new CityState(); peace.setPlayerId(playerId); peace.setStatus("peace"); em.persist(peace);
        } else {
            main = remaining.stream().filter(c -> c.getReadyAt() <= now).findFirst().orElse(remaining.get(0));
            int slot = main.getCitySlot();
            // 主城槽位0有唯一约束，旧城资产已转走并flush后才能提升分城。
            for (Class<? extends CityOwnedEntity> type : CITY_ROWS) rows(type, playerId, slot).forEach(row -> row.setCitySlot(0));
            updateGarrisons(playerId, slot, 0);
            for (MarketOrder order : marketOrders.findBySellerIdOrderByCreatedAtDesc(playerId)) {
                if ("ACTIVE".equals(order.getStatus()) && Integer.valueOf(slot).equals(order.getSellerCitySlot())) order.setSellerCitySlot(0);
            }
            main.setCitySlot(0); main.setReadyAt(Math.min(now, main.getReadyAt()));
            resources.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow().setDiamond(diamonds);
        }
        copyEconomy(main, defender);
        if (defender.getActiveCityId() == null || cities.findById(defender.getActiveCityId())
                .filter(c -> playerId.equals(c.getOwnerId())).isEmpty()) defender.setActiveCityId(main.getId());
        PlayerCity selected = cities.findById(defender.getActiveCityId()).orElse(main);
        defender.setPosX(selected.getX()); defender.setPosY(selected.getY());
        defender.setWarAgainstId(null); defender.setWarAt(0L); defender.setWarEndAt(0L);
    }

    /** 只遍历允许的实体类型，使用受管实体迁移以保留版本及当前事务中的引用。 */
    private <T extends CityOwnedEntity> List<T> rows(Class<T> type, Long playerId, int slot) {
        return em.createQuery("select r from " + type.getSimpleName() + " r where r.playerId = :player and r.citySlot = :slot", type)
                .setParameter("player", playerId).setParameter("slot", slot).getResultList();
    }

    /** 主城经济存于Player，分城经济存于PlayerCity，迁移时须双向同步全部经济字段。 */
    private void copyEconomy(CityEconomy from, CityEconomy to) {
        to.setTax(from.getTax()); to.setMorale(from.getMorale()); to.setResentment(from.getResentment());
        to.setLastAppeaseAt(from.getLastAppeaseAt()); to.setCivilianPopulation(from.getCivilianPopulation());
        to.setPopulationGrowthRemainder(from.getPopulationGrowthRemainder()); to.setLastTick(from.getLastTick());
        to.setCityName(from.getCityName()); to.setCityNameRenamedAt(from.getCityNameRenamedAt());
        to.setCityPosX(from.getCityPosX()); to.setCityPosY(from.getCityPosY());
    }

    /** 下架失守城的委托，把尚未售出的托管资源归还城内，历史成交记录仍属于原玩家。 */
    private void closeMarketOrders(Long playerId, int slot, Resources stock) {
        for (MarketOrder candidate : marketOrders.findBySellerIdOrderByCreatedAtDesc(playerId)) {
            if (!Integer.valueOf(slot).equals(candidate.getSellerCitySlot()) || !"ACTIVE".equals(candidate.getStatus())) continue;
            MarketOrder order = marketOrders.findByIdForUpdate(candidate.getId()).orElseThrow();
            em.refresh(order);
            if (!"ACTIVE".equals(order.getStatus())) continue;
            int amount = order.getAmount();
            switch (order.getResourceType()) {
                case "food" -> stock.setFood(Objects.requireNonNullElse(stock.getFood(), 0) + amount);
                case "steel" -> stock.setSteel(Objects.requireNonNullElse(stock.getSteel(), 0) + amount);
                case "oil" -> stock.setOil(Objects.requireNonNullElse(stock.getOil(), 0) + amount);
                case "rare" -> stock.setRare(Objects.requireNonNullElse(stock.getRare(), 0) + amount);
                default -> throw new IllegalStateException("未知市场资源");
            }
            order.setStatus("CANCELLED"); order.setCompletedAt(LocalDateTime.now());
        }
    }

    /** 失守城派驻的野地兵力也须移除；分城升主城时仅重映射返城槽位。 */
    private void updateGarrisons(Long playerId, int slot, Integer replacement) {
        for (WildTile tile : wilds.findByOccupiedBy(playerId)) {
            // 分城提升为主城只重编号，不改变领地数量与归属；易主在takeOver中转交。
            if (replacement != null && Integer.valueOf(slot).equals(tile.getOccupiedCitySlot())) {
                tile.setOccupiedCitySlot(replacement);
            }
            List<Map<String, Object>> routes = JsonUtil.parseList(tile.getGarrisonRoutes());
            Map<String, Integer> alive = new LinkedHashMap<>(JsonUtil.parseIntMap(tile.getGarrison()));
            Map<String, Integer> unassigned = new LinkedHashMap<>(alive);
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Map<String, Object> route : routes) {
                Number routePlayer = (Number) route.get("playerId");
                int routeSlot = ((Number) route.getOrDefault("citySlot", 0)).intValue();
                boolean affected = routePlayer != null && routePlayer.longValue() == playerId && routeSlot == slot;
                Map<String, Integer> routeArmy = JsonUtil.parseIntMap(JsonUtil.toJson(route.get("army")));
                Map<String, Integer> survivors = new LinkedHashMap<>();
                // 先按现存驻军分配各路线幸存者，再移除失守城编队，不能按原始兵力误删其他城市驻军。
                routeArmy.forEach((unit, count) -> {
                    int countAlive = Math.min(Math.max(0, count), unassigned.getOrDefault(unit, 0));
                    if (countAlive > 0) {
                        survivors.put(unit, countAlive);
                        unassigned.put(unit, unassigned.get(unit) - countAlive);
                    }
                });
                if (affected && replacement == null) {
                    survivors.forEach((unit, count) -> alive.computeIfPresent(unit, (k, n) -> n - count));
                } else {
                    if (affected) route.put("citySlot", replacement);
                    route.put("army", survivors);
                    if (!survivors.isEmpty()) kept.add(route);
                }
            }
            if (Integer.valueOf(slot).equals(tile.getGatherCitySlot())) {
                if (replacement == null) {
                    tile.setGathering(false); tile.setGatherHarvested(null); tile.setGatherStartAt(null); tile.setGatherEndAt(null);
                    tile.setGatherLoad(null); tile.setGatherRes(null);
                    if (routes.isEmpty()) alive.clear();
                } else tile.setGatherCitySlot(replacement);
            }
            alive.entrySet().removeIf(e -> e.getValue() <= 0);
            tile.setGarrison(JsonUtil.toJson(alive)); tile.setGarrisonRoutes(JsonUtil.toJson(kept));
        }
    }

    /** 从现有占地外寻找完整2×2陆地，避免新主城与原城或野地重叠。 */
    private int[] freeCitySite(String mask, Set<String> occupied) {
        int size = com.wargame.model.constants.WorldConfig.WORLD_SIZE;
        int start = new Random().nextInt(size * size);
        for (int i = 0; i < size * size; i++) {
            int n = (start + i) % (size * size), x = n % size, y = n / size;
            if (x + 1 >= size || y + 1 >= size) continue;
            boolean free = true;
            for (int yy = y; yy <= y + 1; yy++) for (int xx = x; xx <= x + 1; xx++) {
                if (WorldTerrainService.sea(mask, xx, yy) || occupied.contains(xx + "," + yy)) free = false;
            }
            if (free) return new int[]{x, y};
        }
        throw new IllegalStateException("地图没有可安置新主城的完整陆地");
    }
}
