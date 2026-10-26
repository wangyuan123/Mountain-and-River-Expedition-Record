package com.wargame.service;

import com.wargame.util.GameClock;
import com.wargame.model.constants.GameData;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.quest.QuestService;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 战俘入池与黄金整编。调用方与正常战损共用事务，所有写入先持有玩家锁。 */
@Service
@RequiredArgsConstructor
@Transactional
public class PrisonerService {
    private final PrisonerUnitRepository prisoners;
    private final BattleSettlementRepository settlements;
    private final PrisonerOperationRepository operations;
    private final BuildingRepository buildings;
    private final ConstructionRepository constructions;
    private final TechnologyRepository technologies;
    private final PlayerCityRepository cities;
    private final ResourcesRepository resources;
    private final ArmyUnitRepository army;
    private final AccountService accounts;
    private final CityScope scope;
    private final WorldTerrainService terrain;
    private final org.springframework.beans.factory.ObjectProvider<QuestService> quests;
    private final EntityManager em;
    private final UnitUnlockService unitUnlocks;

    public record LossSource(String id, Long ownerId, Map<String, Integer> losses, Map<String, Integer> wounded) {}

    public int educationLevel(Long playerId) {
        return technologies.findByPlayerIdAndType(playerId, "prisoner_education").stream().findFirst()
                .map(t -> Math.max(0, Math.min(10, Objects.requireNonNullElse(t.getLevel(), 0)))).orElse(0);
    }

    public int campLevel(Long playerId, int slot) {
        return buildings.findByPlayerIdAndCitySlotAndType(playerId, slot, "prisoner_camp").stream()
                .mapToInt(b -> Math.max(0, Math.min(10, Objects.requireNonNullElse(b.getLevel(), 0)))).max().orElse(0);
    }

    public long total(Long playerId, Long cityId) {
        return prisoners.findByPlayerIdAndCityIdOrderByCreatedAtAscIdAsc(playerId, cityId).stream()
                .mapToLong(PrisonerUnit::getCount).sum();
    }

    /** 玩家锁后的当前读绕过MySQL一致性快照，并刷新已在持久化上下文中的旧实体。 */
    private <T> List<T> currentRows(Class<T> type, String condition, Long playerId, Integer slot) {
        var query = em.createQuery("select r from " + type.getSimpleName() + " r where r.playerId = :player " + condition, type)
                .setParameter("player", playerId).setLockMode(LockModeType.PESSIMISTIC_WRITE);
        if (slot != null) query.setParameter("slot", slot);
        List<T> rows = query.getResultList();
        rows.forEach(row -> em.refresh(row, LockModeType.PESSIMISTIC_WRITE));
        return rows;
    }

    public long currentTotal(Long playerId, Long cityId) {
        return currentRows(PrisonerUnit.class, "and r.cityId = " + cityId, playerId, null).stream()
                .mapToLong(PrisonerUnit::getCount).sum();
    }

    private List<Building> currentBuildings(Long playerId, int slot) {
        return currentRows(Building.class, "and r.citySlot = :slot", playerId, slot);
    }

    private PlayerCity currentCity(Long cityId) {
        PlayerCity city = em.find(PlayerCity.class, cityId, LockModeType.PESSIMISTIC_WRITE);
        if (city != null) em.refresh(city, LockModeType.PESSIMISTIC_WRITE);
        return city;
    }

    /** 每级容量2000；升级按已完成容量，降级施工预留目标容量，防止完工后超员。 */
    private int admissionCapacity(Long playerId, int slot, int completedLevel) {
        int level = completedLevel;
        for (Construction job : currentRows(Construction.class, "and r.citySlot = :slot", playerId, slot)) {
            if ("prisoner_camp".equals(job.getBuildingType())) level = Math.min(level, job.getTargetLevel());
        }
        return Math.max(0, level) * 2000;
    }

    /** 非城市来源或原城失守只能回到胜方现主城，不能使用界面中选中的其他城市。 */
    public PlayerCity destination(Long winnerId, March outbound, PlayerCity defended) {
        if (defended != null) {
            PlayerCity city = currentCity(defended.getId());
            return city != null && winnerId.equals(city.getOwnerId()) && city.getCitySlot() != null ? city : null;
        }
        if (outbound != null) {
            PlayerCity origin = em.createQuery("select c from PlayerCity c where c.ownerId = :owner and c.citySlot is not null order by c.citySlot", PlayerCity.class)
                    .setParameter("owner", winnerId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream()
                    .filter(c -> Objects.equals(c.getX(), outbound.getFromX()) && Objects.equals(c.getY(), outbound.getFromY()))
                    .findFirst().orElse(null);
            if (origin != null) return currentCity(origin.getId());
        }
        return em.createQuery("select c from PlayerCity c where c.ownerId = :owner and c.citySlot = 0", PlayerCity.class)
                .setParameter("owner", winnerId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream()
                .findFirst().map(c -> currentCity(c.getId())).orElse(null);
    }

    /** 已锁定攻守方后，先登记完整战斗凭证。返回空表示整个战斗已结算，不得再发伤兵或战果。 */
    public Optional<BattleSettlement> begin(March march, Long winnerId, long now) {
        String sourceId = march.getId() + ":" + march.getStartAt();
        if (settlements.findBySourceKindAndSourceId("player_battle", sourceId).isPresent()) return Optional.empty();
        BattleSettlement receipt = new BattleSettlement();
        receipt.setSourceKind("player_battle"); receipt.setSourceId(sourceId); receipt.setPlayerId(winnerId); receipt.setCreatedAt(now);
        return Optional.of(settlements.saveAndFlush(receipt));
    }

    /** 从实际参战损失按兵种取整一次，再分配本城空位和原所有者，保留伤兵计数。 */
    public Map<String, Object> capture(BattleSettlement receipt, PlayerCity city, List<LossSource> sources,
                                        Map<String, Integer> participatingLosses, boolean attackerWon, long now) {
        Long winnerId = receipt.getPlayerId();
        if (receipt.getSnapshot() != null) return new LinkedHashMap<>(JsonUtil.parseObjMap(receipt.getSnapshot()));
        city = city == null ? null : currentCity(city.getId());
        if (city != null && (!winnerId.equals(city.getOwnerId()) || city.getCitySlot() == null)) city = null;
        int rate = currentRows(Technology.class, "", winnerId, null).stream()
                .filter(t -> "prisoner_education".equals(t.getType())).mapToInt(t -> Math.max(0, Math.min(10, t.getLevel()))).max().orElse(0);
        int capacity = city == null ? 0 : currentBuildings(winnerId, city.getCitySlot()).stream()
                .filter(b -> "prisoner_camp".equals(b.getType())).mapToInt(b -> Math.max(0, Math.min(10, b.getLevel()))).max().orElse(0) * 2000;
        int admission = city == null ? 0 : admissionCapacity(winnerId, city.getCitySlot(), capacity / 2000);
        long stock = city == null ? 0 : currentTotal(winnerId, city.getId());
        Map<String, Integer> candidates = new TreeMap<>();
        Map<String, Integer> eligible = new TreeMap<>();
        participatingLosses.forEach((unit, lost) -> {
            if (!GameData.UNITS.containsKey(unit)) return;
            int candidate = (int) (Math.max(0L, lost) * rate / 1000);
            long residual = sources.stream().mapToLong(s -> Math.max(0L,
                    (long) s.losses().getOrDefault(unit, 0) - s.wounded().getOrDefault(unit, 0))).sum();
            if (candidate > 0) candidates.put(unit, candidate);
            if (Math.min(candidate, residual) > 0) eligible.put(unit, (int) Math.min(candidate, residual));
        });
        Map<String, Integer> accepted = apportion(eligible, Math.max(0, admission - stock));
        Map<String, Map<String, Integer>> sourceCaptures = new TreeMap<>();
        List<Map<String, Object>> provenance = new ArrayList<>();
        for (var captured : accepted.entrySet()) {
            String unit = captured.getKey();
            Map<String, Integer> weights = new TreeMap<>();
            sources.forEach(s -> weights.put(s.id(), s.losses().getOrDefault(unit, 0)));
            Map<String, Integer> allocation = apportion(weights, captured.getValue());
            // 极端小编队也不能侵占原伤兵；剩余名额按同样规则重新分给仍有永久损失的来源。
            Map<String, Integer> room = new TreeMap<>();
            for (LossSource source : sources) {
                int residual = Math.max(0, source.losses().getOrDefault(unit, 0) - source.wounded().getOrDefault(unit, 0));
                allocation.computeIfPresent(source.id(), (id, amount) -> Math.min(amount, residual));
                room.put(source.id(), Math.max(0, residual - allocation.getOrDefault(source.id(), 0)));
            }
            int allocated = allocation.values().stream().mapToInt(Integer::intValue).sum();
            apportion(room, captured.getValue() - allocated).forEach((id, count) -> allocation.merge(id, count, Integer::sum));
            for (LossSource source : sources) {
                int count = allocation.getOrDefault(source.id(), 0);
                if (count <= 0) continue;
                PrisonerUnit batch = new PrisonerUnit();
                batch.setPlayerId(winnerId); batch.setCityId(city.getId()); batch.setCitySlot(city.getCitySlot());
                batch.setSettlementId(receipt.getId()); batch.setSourceId(source.id()); batch.setOriginalOwnerId(source.ownerId());
                batch.setType(unit); batch.setOriginalCount(count); batch.setCount(count); batch.setCreatedAt(now);
                prisoners.save(batch);
                sourceCaptures.computeIfAbsent(source.id(), id -> new TreeMap<>()).put(unit, count);
            }
        }
        for (LossSource source : sources) {
            source.losses().forEach((unit, losses) -> {
                int wounded = source.wounded().getOrDefault(unit, 0);
                int captured = sourceCaptures.getOrDefault(source.id(), Map.of()).getOrDefault(unit, 0);
                provenance.add(Map.of("sourceId", source.id(), "ownerId", source.ownerId(), "unit", unit,
                        "losses", losses, "wounded", wounded, "captured", captured, "permanent", Math.max(0, losses - wounded - captured)));
            });
        }
        long candidateTotal = candidates.values().stream().mapToLong(Integer::longValue).sum();
        long acceptedTotal = accepted.values().stream().mapToLong(Integer::longValue).sum();
        String reason = acceptedTotal > 0 ? "accepted" : rate == 0 ? "unresearched" : capacity == 0 ? "no_camp"
                : candidateTotal == 0 ? "below_threshold" : "capacity_full";
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ruleVersion", "prisoners-2026-10-08-v1"); snapshot.put("winnerId", winnerId);
        snapshot.put("attackerWon", attackerWon); snapshot.put("cityId", city == null ? null : city.getId());
        snapshot.put("cityName", city == null ? "主城" : city.getName()); snapshot.put("ratePermille", rate);
        snapshot.put("capacity", capacity); snapshot.put("admissionCapacity", admission); snapshot.put("existing", stock);
        snapshot.put("candidates", candidates); snapshot.put("accepted", accepted); snapshot.put("sources", provenance);
        snapshot.put("candidateTotal", candidateTotal); snapshot.put("acceptedTotal", acceptedTotal);
        snapshot.put("overflow", candidateTotal - acceptedTotal); snapshot.put("reason", reason);
        receipt.setCityId(city == null ? null : city.getId()); receipt.setSnapshot(JsonUtil.toJson(snapshot)); settlements.save(receipt);
        return snapshot;
    }

    /** 最大余数法；相同余数按稳定键排序，不随机、不跨兵种产生额外候选，也不保底。 */
    public static Map<String, Integer> apportion(Map<String, Integer> weights, long requested) {
        long total = weights.values().stream().mapToLong(n -> Math.max(0, n)).sum();
        long amount = Math.min(total, Math.max(0, requested));
        Map<String, Integer> result = new TreeMap<>();
        if (total == 0 || amount == 0) return result;
        Map<String, Long> remainder = new HashMap<>();
        long assigned = 0;
        for (var entry : weights.entrySet()) {
            long product = Math.max(0L, entry.getValue()) * amount;
            int count = (int) (product / total);
            result.put(entry.getKey(), count); remainder.put(entry.getKey(), product % total); assigned += count;
        }
        List<String> order = new ArrayList<>(weights.keySet());
        order.sort(Comparator.<String>comparingLong(remainder::get).reversed().thenComparing(Comparator.naturalOrder()));
        for (int i = 0; i < amount - assigned; i++) result.merge(order.get(i), 1, Integer::sum);
        result.values().removeIf(n -> n == 0);
        return result;
    }

    /** 按当前服务端制造资源总价的25%向上取整，只收黄金。 */
    public long goldPerUnit(String unit) {
        var def = GameData.UNITS.get(unit);
        if (def == null) throw new IllegalArgumentException("未知兵种");
        long cost = def.cost().values().stream().mapToLong(Integer::longValue).sum();
        return Math.max(1L, (cost + 3) / 4);
    }

    /** 黄金招募与普通征召共用制造前置，当前读仍沿用玩家锁后的实体刷新。 */
    private String unavailable(Long playerId, PlayerCity city, String unit, boolean current) {
        return unitUnlocks.message(unitUnlocks.evaluate(playerId, city.getCitySlot(), unit, current));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPool(Long playerId) {
        PlayerCity city = scope.selected(playerId).orElseThrow(() -> new IllegalArgumentException("城市不存在"));
        Map<String, Integer> counts = new TreeMap<>();
        prisoners.findByPlayerIdAndCityIdOrderByCreatedAtAscIdAsc(playerId, city.getId())
                .forEach(b -> counts.merge(b.getType(), b.getCount(), Integer::sum));
        List<Map<String, Object>> units = new ArrayList<>();
        counts.forEach((unit, count) -> units.add(Map.of("unit", unit, "count", count, "goldPerUnit", goldPerUnit(unit),
                "unavailableReason", unavailable(playerId, city, unit, false),
                "unlock", unitUnlocks.evaluate(playerId, city.getCitySlot(), unit, false))));
        int level = campLevel(playerId, city.getCitySlot());
        int admission = admissionCapacity(playerId, city.getCitySlot(), level);
        return Map.of("cityId", city.getId(), "cityName", city.getName(), "units", units,
                "total", counts.values().stream().mapToLong(Integer::longValue).sum(), "capacity", level * 2000,
                "admissionCapacity", admission, "campLevel", level, "educationLevel", educationLevel(playerId), "ratePermille", educationLevel(playerId));
    }

    /** 相同凭证重试返回原结果；任何校验失败均回滚黄金、库存、军队与凭证。 */
    public Map<String, Object> operate(Long playerId, String action, String unit, Integer count, String operationKey) {
        if (!GameData.UNITS.containsKey(unit == null ? "" : unit) || count == null || count <= 0)
            throw new IllegalArgumentException("请输入有效兵种和数量");
        if (!Set.of("recruit", "release").contains(action) || operationKey == null || !operationKey.matches("[A-Za-z0-9_-]{8,80}"))
            throw new IllegalArgumentException("操作凭证无效");
        PlayerCity selected = scope.selected(playerId).orElseThrow(() -> new IllegalArgumentException("城市不存在"));
        return operateInCity(playerId, selected.getId(), action, unit, count, operationKey);
    }

    /** 稳定城市ID来自请求头；锁等待后的槽位变动不能将请求改投另一城市。 */
    public Map<String, Object> operateInCity(Long playerId, Long cityId, String action, String unit, Integer count, String operationKey) {
        if (!GameData.UNITS.containsKey(unit == null ? "" : unit) || count == null || count <= 0)
            throw new IllegalArgumentException("请输入有效兵种和数量");
        if (!Set.of("recruit", "release").contains(action) || operationKey == null || !operationKey.matches("[A-Za-z0-9_-]{8,80}"))
            throw new IllegalArgumentException("操作凭证无效");
        accounts.lockPlayer(playerId);
        PlayerCity city = currentCity(cityId);
        if (city == null || !playerId.equals(city.getOwnerId()) || city.getCitySlot() == null
                || city.getCitySlot() != scope.slot(playerId)) throw new IllegalArgumentException("城市归属或槽位已变化，请重新打开俘虏池");
        if (city.getReadyAt() > GameClock.now()) throw new IllegalArgumentException("城市仍在建设中");
        Optional<PrisonerOperation> previous = operations.findByPlayerIdAndOperationKey(playerId, operationKey);
        if (previous.isPresent()) {
            PrisonerOperation receipt = previous.get();
            if (!receipt.getCityId().equals(city.getId()) || !receipt.getAction().equals(action)
                    || !receipt.getType().equals(unit) || !receipt.getCount().equals(count))
                throw new IllegalArgumentException("操作凭证已用于其他请求");
            return new LinkedHashMap<>(JsonUtil.parseObjMap(receipt.getResult()));
        }
        List<PrisonerUnit> batches = currentRows(PrisonerUnit.class, "and r.cityId = " + city.getId() + " order by r.createdAt, r.id", playerId, null).stream()
                .filter(b -> unit.equals(b.getType())).toList();
        if (batches.stream().mapToLong(PrisonerUnit::getCount).sum() < count) throw new IllegalArgumentException("数量超过本城剩余战俘");
        long cost = 0;
        if ("recruit".equals(action)) {
            String reason = unavailable(playerId, city, unit, true);
            if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
            Resources balance = resources.findCityForTreatment(playerId, city.getCitySlot()).orElseThrow();
            em.refresh(balance, LockModeType.PESSIMISTIC_WRITE);
            cost = Math.multiplyExact(goldPerUnit(unit), count);
            long gold = Objects.requireNonNullElse(balance.getGold(), 0L);
            if (gold < cost) throw new IllegalArgumentException("黄金不足，请减少招募数量");
            balance.setGold(gold - cost); resources.save(balance);
            List<ArmyUnit> existing = currentRows(ArmyUnit.class, "and r.citySlot = :slot", playerId, city.getCitySlot()).stream()
                    .filter(a -> unit.equals(a.getType())).toList();
            ArmyUnit recruited = existing.isEmpty() ? new ArmyUnit(null, playerId, unit, 0) : existing.get(0);
            recruited.setCitySlot(city.getCitySlot()); recruited.setCount(Math.addExact(recruited.getCount(), count)); army.save(recruited);
        }
        int remaining = count;
        for (PrisonerUnit batch : batches) {
            int taken = Math.min(remaining, batch.getCount()); batch.setCount(batch.getCount() - taken); remaining -= taken;
            if (batch.getCount() == 0) prisoners.delete(batch); else prisoners.save(batch);
            if (remaining == 0) break;
        }
        // 仅更新军队库存目标，不能触发生产完成、征召次数或训练声望。
        if ("recruit".equals(action)) { quests.getObject().onEvent(playerId, "ARMY_TOTAL"); quests.getObject().onEvent(playerId, "ARMY_NAVY_TOTAL"); }
        Map<String, Object> result = new LinkedHashMap<>(Map.of("success", true, "count", count, "cost", cost,
                "cityId", city.getId(), "message", "recruit".equals(action) ? "招募完成，部队已在本城归队" : "已释放战俘，无资源奖励"));
        PrisonerOperation receipt = new PrisonerOperation();
        receipt.setPlayerId(playerId); receipt.setCityId(city.getId()); receipt.setOperationKey(operationKey);
        receipt.setAction(action); receipt.setType(unit); receipt.setCount(count); receipt.setCreatedAt(GameClock.now());
        receipt.setResult(JsonUtil.toJson(result)); operations.save(receipt);
        return result;
    }

    public void clearAccount(Long playerId) {
        prisoners.deleteByPlayerId(playerId); operations.deleteByPlayerId(playerId); settlements.deleteByPlayerId(playerId);
    }
}
