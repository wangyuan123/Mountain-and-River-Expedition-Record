package com.wargame.service;

import com.wargame.util.DurationText;

import com.wargame.util.GameClock;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.BuildingDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 军队征召服务 - 对应 JS 中 G.Army 的后端实现。
 * <p>
 * 核心逻辑参考 js/army.js 和 js/core.js：
 * <ul>
 *   <li>{@code G.Army.recruit}  -> {@link #recruit}</li>
 *   <li>{@code G.Army.disband}  -> {@link #dismiss}</li>
 *   <li>{@code G.Army.totalArmy}-> {@link #totalArmy}</li>
 *   <li>{@code Core.trainMul}   -> {@link #trainMul}</li>
 *   <li>{@code Core.armyCap}    -> {@link #armyCap}</li>
 *   <li>{@code Core.popFree}    -> {@link #popFree}</li>
 * </ul>
 */
@Service
public class ArmyService {
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private GarrisonFoodService garrisonFood;
    @org.springframework.beans.factory.annotation.Autowired private OfficerEquipmentProgressService equipmentProgress;


    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired
    private PrerequisiteService prerequisiteService;
    @org.springframework.beans.factory.annotation.Autowired private UnitUnlockService unitUnlocks;

    private static final int MAX_QUEUE_CAPACITY = 500;

    private final ArmyUnitRepository armyUnitRepository;
    private final ArmyProductionQueueRepository armyProductionQueueRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;
    private final TechnologyRepository technologyRepository;
    private final OfficerRepository officerRepository;
    private final PlayerRepository playerRepository;
    private final SpeedUpSupport speedUpSupport;
    private final com.wargame.service.quest.QuestService questService;

    public ArmyService(ArmyUnitRepository armyUnitRepository,
                       ArmyProductionQueueRepository armyProductionQueueRepository,
                       ResourcesRepository resourcesRepository,
                       BuildingRepository buildingRepository,
                       TechnologyRepository technologyRepository,
                       OfficerRepository officerRepository,
                       PlayerRepository playerRepository,
                       SpeedUpSupport speedUpSupport,
                       com.wargame.service.quest.QuestService questService) {
        this.armyUnitRepository = armyUnitRepository;
        this.armyProductionQueueRepository = armyProductionQueueRepository;
        this.resourcesRepository = resourcesRepository;
        this.buildingRepository = buildingRepository;
        this.technologyRepository = technologyRepository;
        this.officerRepository = officerRepository;
        this.playerRepository = playerRepository;
        this.speedUpSupport = speedUpSupport;
        this.questService = questService;
    }

    // ================================================================
    //  recruit - 对应 JS G.Army.recruit
    // ================================================================

    @Transactional
    public Map<String, Object> recruit(Long playerId, String unitType, int count) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 校验兵种 (JS: var u = D.units[id])
        UnitDef u = GameData.UNITS.get(unitType);
        if (u == null) {
            result.put("success", false);
            result.put("message", "无效的兵种: " + unitType);
            return result;
        }

        // 与建造/拆除共用玩家锁，防止校验之后军港或造船厂被并发拆除。
        Player player = playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        completeProduction(playerId, GameClock.now());

        // 在扣款与人口扣减前统一校验；海军同时需要本城造船厂和军港。
        var unlock = unitUnlocks.evaluate(playerId, cityScope.slot(playerId), unitType, true);
        result.put("unlock", unlock);
        var missing = unlock.missing();
        if (!missing.isEmpty()) {
            result.put("success", false);
            result.put("message", prerequisiteService.message(missing));
            result.put("missingPrerequisites", missing);
            result.put("maxRecruitable", 0);
            return result;
        }

        // 2. 列出所有该兵种对应的训练建筑 - 方案B: 按栋独立计算后求和
        //    不再使用"sum(blv)"的全局求和公式，改为遍历每栋兵工厂：
        //      maxBatch_f = level_f × 10 × trainMul
        //      parallel_f = 1 + level_f / 5
        //      speed_f    = 1 + min(4, level_f × 0.05) + (trainMul - 1)
        List<Building> factoryBuildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), u.build());
        if (factoryBuildings.isEmpty()) {
            BuildingDef bDef = GameData.BUILDINGS.get(u.build());
            String bName = bDef != null ? bDef.name() : u.build();
            result.put("success", false);
            result.put("message", "需先建造: " + bName);
            result.put("maxRecruitable", 0);
            return result;
        }

        double trMul = trainMul(playerId);
        int totalMaxBatch = 0;
        int totalParallel = 0;
        double totalWeightedSpeed = 0;
        int activeLanes = 0; // 等级 > 0 的兵工厂参与求和
        for (Building fb : factoryBuildings) {
            int lv = fb.getLevel() != null ? fb.getLevel() : 0;
            if (lv <= 0) continue;
            totalMaxBatch += (int) Math.floor(lv * 10 * trMul);
            totalMaxBatch = Math.min(totalMaxBatch, MAX_QUEUE_CAPACITY);
            int lanes = 1 + lv / 5;
            totalParallel += lanes;
            double laneSpeed = 1.0 + Math.min(4.0, lv * 0.05) + Math.max(0, trMul - 1.0);
            totalWeightedSpeed += laneSpeed * lanes;
            activeLanes += lanes;
        }
        if (totalMaxBatch <= 0) {
            result.put("success", false);
            result.put("message", "训练建筑等级过低");
            return result;
        }
        int parallel = Math.max(1, Math.min(20, totalParallel));
        double avgSpeed = activeLanes > 0 ? totalWeightedSpeed / activeLanes : 1.0;

        // 3. 单次可征召数由当前平民与资源共同决定；生产队列按并行通道排期，不再用兵工厂等级限制数量。
        int civilians = player != null && cityScope.economy(playerId).getCivilianPopulation() != null ? cityScope.economy(playerId).getCivilianPopulation() : 0;
        Resources resources = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        int maxByPopulation = u.pop() > 0 ? civilians / u.pop() : Integer.MAX_VALUE;
        int maxByResources = maxAffordableByResources(resources, u.cost());
        int dynamicMaxBatch = Math.min(maxByPopulation, maxByResources);
        if (count > dynamicMaxBatch) {
            result.put("success", false);
            result.put("message", "当前最多可征召 " + dynamicMaxBatch + " 个（受平民和资源限制），当前输入 " + count);
            result.put("maxBatch", dynamicMaxBatch);
            return result;
        }
        int n = count;
        if (n <= 0) {
            result.put("success", false);
            result.put("message", "征召数量无效");
            return result;
        }

        // 5. 计算费用 (JS: var cost = unitCost(id, n))
        //    unitCost: cost[k] = u.cost[k] * n; cost.pop = u.pop * n
        Map<String, Integer> cost = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : u.cost().entrySet()) {
            cost.put(entry.getKey(), entry.getValue() * n);
        }
        cost.put("pop", u.pop() * n);

        // 6. 检查资源 (JS: if (!Core.costEnough(cost)))
        if (!costEnough(playerId, cost)) {
            result.put("success", false);
            result.put("message", "资源不足");
            return result;
        }

        // 7. 检查持久化平民人口；征兵创建时立即扣除。
        int popNeeded = u.pop() * n;
        if (civilians < popNeeded) {
            result.put("success", false);
            result.put("message", "可征召平民不足 (需" + popNeeded + ")");
            return result;
        }

        // 8. 扣除资源 (JS: Core.payCost(cost)) - pop 不从资源中扣除, 仅检查
        Map<String, Integer> resCost = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : cost.entrySet()) {
            if (!"pop".equals(entry.getKey())) {
                resCost.put(entry.getKey(), entry.getValue());
            }
        }
        deductCosts(playerId, resCost);

        // 10. 增加声望 (JS: Core.addPrestige(cost))
        int totalCost = 0;
        for (int v : resCost.values()) totalCost += v;
        int prestigeGain = totalCost > 0 ? Math.max(1, totalCost / 100) : 0;
        if (player != null) {
            cityScope.economy(playerId).setCivilianPopulation(civilians - popNeeded);
            if (prestigeGain > 0) {
                player.setPrestige((player.getPrestige() != null ? player.getPrestige() : 0) + prestigeGain);
            }
            playerRepository.save(player);
        cityScope.saveEconomy(playerId);
        }

        long now = GameClock.now();
        // 方案B: parallel 与 avgSpeed 已在上方按栋独立计算后求和得到, 这里直接使用
        int baseSeconds = 30 + u.cost().values().stream().mapToInt(Integer::intValue).sum() / 10;
        int duration = Math.max(5, (int) Math.ceil(baseSeconds * n / avgSpeed));
        long startsAt = productionStartsAt(playerId, u.build(), parallel, now);
        ArmyProductionQueue queue = new ArmyProductionQueue();
        queue.setPlayerId(playerId);
        queue.setCitySlot(cityScope.slot(playerId));
        queue.setUnitType(unitType);
        queue.setUnlockRuleVersion(unlock.ruleVersion());
        queue.setUnlockRequirements(unlock.snapshot());
        queue.setBuildingType(u.build());
        queue.setUnitCount(n);
        queue.setStartedAt(startsAt);
        queue.setFinishesAt(startsAt + duration * 1000L);
        queue.setDurationSeconds(duration);
        queue.setSpeedMultiplier(avgSpeed);
        queue.setCostFood(cost.getOrDefault("food", 0));
        queue.setCostSteel(cost.getOrDefault("steel", 0));
        queue.setCostOil(cost.getOrDefault("oil", 0));
        queue.setCostRare(cost.getOrDefault("rare", 0));
        armyProductionQueueRepository.save(queue);

        result.put("success", true);
        long waitingMs = Math.max(0, queue.getStartedAt() - now);
        result.put("message", "已加入生产队列 " + u.name() + " x" + n + "，自身工期 " + DurationText.seconds(duration)
                + (waitingMs > 0 ? "，排队等待 " + DurationText.millis(waitingMs) : "，立即开工")
                + "，预计交付 " + DurationText.millis(Math.max(0, queue.getFinishesAt() - now)));
        result.put("unitType", unitType);
        result.put("count", n);
        result.put("durationSeconds", duration);
        result.put("finishesAt", queue.getFinishesAt());
        result.put("queueId", queue.getId());
        result.put("parallelQueues", parallel);
        result.put("prestigeGain", prestigeGain);
        return result;
    }

    // ================================================================
    //  dismiss - 对应 JS G.Army.disband
    // ================================================================

    @Transactional
    public Map<String, Object> dismiss(Long playerId, String unitType, int count) {
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        UnitDef u = GameData.UNITS.get(unitType);
        if (u == null) {
            result.put("success", false);
            result.put("message", "无效的兵种: " + unitType);
            return result;
        }

        // JS: var have = s.army[id] || 0; if (have <= 0)
        List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), unitType);
        int have = 0;
        ArmyUnit unit = null;
        if (existing != null && !existing.isEmpty()) {
            unit = existing.get(0);
            have = unit.getCount() != null ? unit.getCount() : 0;
        }
        if (have <= 0) {
            result.put("success", false);
            result.put("message", "无该兵种可解散");
            return result;
        }

        // JS: n = Math.min(n, have)
        int n = Math.min(count, have);

        // JS: s.army[id] = have - n
        unit.setCount(have - n);
        armyUnitRepository.save(unit);

        Player player = playerRepository.findById(playerId).orElse(null);
        if (player != null) {
            int civilians = cityScope.economy(playerId).getCivilianPopulation() != null ? cityScope.economy(playerId).getCivilianPopulation() : 0;
            cityScope.economy(playerId).setCivilianPopulation(Math.min(popMax(playerId), civilians + u.pop() * n));
            playerRepository.save(player);
        cityScope.saveEconomy(playerId);
        }

        // JS: s.resources.steel += Math.floor(D.units[id].cost.steel * n * 0.3)
        int steelCost = u.cost().getOrDefault("steel", 0);
        int refund = (int) Math.floor(steelCost * n * 0.3);
        if (refund > 0) {
            Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
            if (res != null) {
                res.addResource("steel", refund);
                resourcesRepository.save(res);
            }
        }

        result.put("success", true);
        result.put("message", "解散 " + u.name() + " x" + n + " 回收钢" + refund);
        result.put("count", n);
        result.put("refund", refund);
        return result;
    }

    // ================================================================
    //  getArmy - 返回所有军队
    // ================================================================

    @Transactional
    public List<Map<String, Object>> getProductionQueue(Long playerId) {
        completeProduction(playerId, GameClock.now());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ArmyProductionQueue q : armyProductionQueueRepository.findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(playerId, cityScope.slot(playerId))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", q.getId()); item.put("unitType", q.getUnitType()); item.put("count", q.getUnitCount());
            item.put("startedAt", q.getStartedAt()); item.put("finishesAt", q.getFinishesAt());
            item.put("durationSeconds", q.getDurationSeconds());
            item.put("remainingSeconds", Math.max(0, (q.getFinishesAt() - GameClock.now() + 999) / 1000));
            result.add(item);
        }
        return result;
    }

    @Transactional
    public boolean cancelProduction(Long playerId, Long queueId) {
        ArmyProductionQueue q = armyProductionQueueRepository.findById(queueId).orElse(null);
        if (q == null || !playerId.equals(q.getPlayerId()) || q.getCitySlot() != cityScope.slot(playerId)) return false;
        armyProductionQueueRepository.delete(q);
        refund(playerId, q.getCostFood(), q.getCostSteel(), q.getCostOil(), q.getCostRare());
        UnitDef unit = GameData.UNITS.get(q.getUnitType());
        Player player = playerRepository.findById(playerId).orElse(null);
        if (unit != null && player != null) {
            int civilians = cityScope.economy(playerId).getCivilianPopulation() != null ? cityScope.economy(playerId).getCivilianPopulation() : 0;
            cityScope.economy(playerId).setCivilianPopulation(Math.min(popMax(playerId), civilians + unit.pop() * q.getUnitCount()));
            playerRepository.save(player);
        cityScope.saveEconomy(playerId);
        }
        return true;
    }

    @Transactional
    public void completeProduction(Long playerId, long now) {
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        for (ArmyProductionQueue q : armyProductionQueueRepository.findByPlayerIdAndCitySlotAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(playerId, cityScope.slot(playerId), now)) {
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), q.getUnitType());
            ArmyUnit unit = existing.isEmpty() ? new ArmyUnit(null, playerId, q.getUnitType(), 0) : existing.get(0);
            unit.setCitySlot(q.getCitySlot());
            unit.setCount((unit.getCount() == null ? 0 : unit.getCount()) + q.getUnitCount());
            armyUnitRepository.save(unit);
            equipmentProgress.event(playerId, "train", "production-" + q.getId(), q.getUnitCount());
            armyProductionQueueRepository.delete(q);
            try { questService.onEvent(playerId, "ARMY_RECRUIT", q.getUnitType(), q.getUnitCount()); questService.onEvent(playerId, "ARMY_TOTAL"); } catch (Exception ignored) {}
        }
    }

    /** 免费完成当前城市的指定部队生产任务，沿用正常完工的入库和任务结算。 */
    @Transactional
    public Map<String, Object> freeSpeedUp(Long playerId, Long queueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        ArmyProductionQueue target = null;
        if (queueId != null) {
            target = armyProductionQueueRepository.findById(queueId).orElse(null);
        } else {
            List<ArmyProductionQueue> list = armyProductionQueueRepository.findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(playerId, cityScope.slot(playerId));
            if (!list.isEmpty()) target = list.get(0);
        }
        if (target == null || !playerId.equals(target.getPlayerId()) || target.getCitySlot() != cityScope.slot(playerId)) {
            result.put("message", "指定的生产任务不存在或已完成");
            return result;
        }
        long now = GameClock.now();
        if (target.getFinishesAt() != null && target.getFinishesAt() - now > 300 * 1000L) {
            result.put("message", "剩余生产时间不超过5分钟时才可免费加速");
            return result;
        }
        shortenProductionOrder(playerId, target, now, now);
        completeProduction(playerId, now);
        result.put("success", true);
        result.put("completed", true);
        result.put("queueId", target.getId());
        result.put("message", "⚡ 免费加速成功，部队生产完成！");
        return result;
    }

    // ================================================================
    //  useSpeedUp - 使用加速符对指定生产订单立即完成或缩短时间
    // ================================================================

    @Transactional
    public Map<String, Object> useSpeedUp(Long playerId, String itemId, Long queueId) {
        return useSpeedUp(playerId, itemId, queueId, 1);
    }

    @Transactional
    public Map<String, Object> useSpeedUp(Long playerId, String itemId, Long queueId, int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        long now = GameClock.now();
        if (count <= 0) count = 1;

        // 1. 校验道具并原子扣减 count 个
        com.wargame.model.constants.ItemDef item = speedUpSupport.consume(playerId, itemId, count, now, result);
        if (item == null) return result;
        long reduceMs = item.speedUpSeconds() * 1000L * count;

        // 2. 找到目标生产订单
        ArmyProductionQueue target = null;
        if (queueId != null) {
            target = armyProductionQueueRepository.findById(queueId).orElse(null);
            if (target == null || !playerId.equals(target.getPlayerId()) || target.getCitySlot() != cityScope.slot(playerId)) {
                speedUpSupport.refund(playerId, itemId, count, now);
                result.put("success", false);
                result.put("message", "指定的生产任务不存在");
                return result;
            }
        } else {
            // 兼容旧版未传 queueId 时, 取最早一个未完成的订单
            for (ArmyProductionQueue q : armyProductionQueueRepository.findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(playerId, cityScope.slot(playerId))) {
                if (q.getFinishesAt() != null && q.getFinishesAt() > now) { target = q; break; }
            }
        }
        if (target == null) {
            speedUpSupport.refund(playerId, itemId, count, now);
            result.put("success", false);
            result.put("message", "当前无生产中部队");
            return result;
        }

        // 3. 计算新 finish_at
        long oldFinishAt = target.getFinishesAt() != null ? target.getFinishesAt() : now;
        long newFinishAt = oldFinishAt - reduceMs;
        long remainingMs = newFinishAt - now;

        boolean completed = remainingMs <= 0;
        shortenProductionOrder(playerId, target, Math.max(now, newFinishAt), now);
        if (completed) completeProduction(playerId, now);

        // 4. 清理 0 数量道具记录
        speedUpSupport.cleanupZeroCount(playerId, itemId);

        result.put("success", true);
        result.put("completed", completed);
        result.put("itemId", itemId);
        result.put("itemName", item.name());
        result.put("queueId", target.getId());
        result.put("unitType", target.getUnitType());
        result.put("count", target.getUnitCount());
        result.put("speedUpCount", count);
        if (completed) {
            result.put("message", "⚡ " + item.name() + " ×" + count + " 使用成功，造兵完成!");
        } else {
            long remainSec = Math.max(0, remainingMs / 1000);
            result.put("message", "⚡ " + item.name() + " ×" + count + " 使用成功，剩余 " + DurationText.millis(remainingMs));
            result.put("remainingSeconds", remainSec);
        }
        return result;
    }

    /** 缩短订单后按原并行通道前移等待订单；已开工订单和其他制造设施不受影响。 */
    private void shortenProductionOrder(Long playerId, ArmyProductionQueue target, long finishAt, long now) {
        String building = productionBuilding(target);
        int parallel = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), building)
                .stream().mapToInt(b -> 1 + Objects.requireNonNullElse(b.getLevel(), 0) / 5).sum();
        parallel = Math.max(1, parallel);
        long[] oldAvailable = new long[parallel];
        long[] newAvailable = new long[parallel];
        for (ArmyProductionQueue q : armyProductionQueueRepository.findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(playerId, cityScope.slot(playerId))) {
            if (!Objects.equals(building, productionBuilding(q))) continue;
            int lane = 0;
            for (int i = 1; i < parallel; i++) if (oldAvailable[i] < oldAvailable[lane]) lane = i;
            long oldStart = q.getStartedAt(), oldFinish = q.getFinishesAt();
            if (q.getId().equals(target.getId())) {
                q.setFinishesAt(finishAt);
                q.setStartedAt(Math.min(oldStart, finishAt));
                armyProductionQueueRepository.save(q);
            } else if (oldStart > now && newAvailable[lane] < oldAvailable[lane]) {
                // 只移动等待段，保留订单自身已锁定的工期；超额加速不能转成后单的工作量。
                long start = Math.min(oldStart, Math.max(now, newAvailable[lane]));
                q.setStartedAt(start);
                q.setFinishesAt(start + oldFinish - oldStart);
                armyProductionQueueRepository.save(q);
            }
            oldAvailable[lane] = Math.max(oldAvailable[lane], oldFinish);
            newAvailable[lane] = Math.max(newAvailable[lane], q.getFinishesAt());
        }
    }

    /** 历史军港造舰订单与新造船厂订单属于同一种生产设施。 */
    private String productionBuilding(ArmyProductionQueue q) {
        UnitDef unit = GameData.UNITS.get(q.getUnitType());
        String building = q.getBuildingType() != null ? q.getBuildingType() : unit != null ? unit.build() : null;
        return "port".equals(building) && unit != null && "sea".equals(unit.branch()) ? unit.build() : building;
    }

    private long productionStartsAt(Long playerId, String buildingType, int parallel, long now) {
        List<Long> laneAvailableAt = new ArrayList<>();
        for (int i = 0; i < parallel; i++) laneAvailableAt.add(now);
        for (ArmyProductionQueue q : armyProductionQueueRepository.findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(playerId, cityScope.slot(playerId))) {
            UnitDef queuedUnit = GameData.UNITS.get(q.getUnitType());
            String queueBuilding = q.getBuildingType() != null ? q.getBuildingType()
                    : queuedUnit != null ? queuedUnit.build() : null;
            // 旧军港造舰订单继续占用造船通道，设施拆分不能额外增加并行制造能力。
            if ("port".equals(queueBuilding) && queuedUnit != null && "sea".equals(queuedUnit.branch())) {
                queueBuilding = queuedUnit.build();
            }
            if (!buildingType.equals(queueBuilding)) continue;
            int lane = 0;
            for (int i = 1; i < laneAvailableAt.size(); i++) if (laneAvailableAt.get(i) < laneAvailableAt.get(lane)) lane = i;
            laneAvailableAt.set(lane, Math.max(laneAvailableAt.get(lane), q.getFinishesAt()));
        }
        return Collections.min(laneAvailableAt);
    }

    private void refund(Long playerId, int food, int steel, int oil, int rare) {
        Resources r = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null); if (r == null) return;
        r.addResource("food", food); r.addResource("steel", steel);
        r.addResource("oil", oil); r.addResource("rare", rare); resourcesRepository.save(r);
    }

    public Map<String, Integer> getArmy(Long playerId) {
        completeProduction(playerId, GameClock.now());
        Map<String, Integer> army = new LinkedHashMap<>();
        List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        for (ArmyUnit unit : units) {
            int count = unit.getCount() != null ? unit.getCount() : 0;
            if (count > 0) {
                army.put(unit.getType(), count);
            }
        }
        return army;
    }

    // ================================================================
    //  totalArmy - 对应 JS G.Army.totalArmy
    // ================================================================

    public int totalArmy(Long playerId) {
        int sum = 0;
        List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        for (ArmyUnit unit : units) {
            sum += unit.getCount() != null ? unit.getCount() : 0;
        }
        return sum;
    }

    // ================================================================
    //  trainMul - 对应 JS Core.trainMul: 1 + 0.05 * log_train
    // ================================================================

    public double trainMul(Long playerId) {
        int logTrain = getTechLevel(playerId, "log_train");
        return 1 + 0.05 * logTrain;
    }

    // ================================================================
    //  armyCap - 对应 JS Core.armyCap
    //  少校基础 300,000，此后每阶 +50,000，上将 600,000。
    //  三军统帅技能只放大军衔基础；城防建筑与其他建筑、指挥官等级均不增加带兵上限。
    // ================================================================

    public int armyCap(Long playerId) {
        Player player = playerRepository.findById(playerId).orElse(null);
        int rankTier = (player != null && player.getMilitaryRank() != null) ? player.getMilitaryRank() : 1;
        int rankBase = MilitaryRankDef.getRankBase(rankTier);

        int leadershipLv = 0;
        List<Officer> commanders = officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, cityScope.slot(playerId), "commander");
        if (commanders != null && !commanders.isEmpty()) {
            Officer cmd = commanders.get(0);
            leadershipLv = getOfficerSkillLevel(cmd, "leadership");
            if (leadershipLv == 0) {
                // 兼容历史老存档 supply
                leadershipLv = getOfficerSkillLevel(cmd, "supply");
            }
        }

        double cap = rankBase;
        if (leadershipLv > 0) {
            cap *= (1.0 + 0.04 * leadershipLv);
        }
        return (int) Math.floor(cap);
    }

    /** 出城迎战只额外增加半份军衔基础，统帅加成保持与攻击相同的数额。 */
    public int sortieCap(Long playerId) {
        Player player = playerRepository.findById(playerId).orElse(null);
        int rankBase = MilitaryRankDef.getRankBase(player != null ? player.getMilitaryRank() : 1);
        return armyCap(playerId) + rankBase / 2;
    }

    private int getOfficerSkillLevel(Officer officer, String skillId) {
        if (officer == null || officer.getSkills() == null || officer.getSkills().isBlank()) return 0;
        try {
            List<Map<String, Object>> list = JsonUtil.parseList(officer.getSkills());
            for (Map<String, Object> sk : list) {
                if (skillId.equals(sk.get("id"))) {
                    Object lv = sk.get("lv");
                    return lv instanceof Number ? ((Number) lv).intValue() : 0;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ================================================================
    //  popMax - 对应 JS Core.popMax: buildingLevel('house') * popPer
    // ================================================================

    public int popMax(Long playerId) {
        int houseLv = buildingLevel(playerId, "house");
        BuildingDef houseDef = GameData.BUILDINGS.get("house");
        int popPer = houseDef != null && houseDef.popPer() != null ? houseDef.popPer() : 1200;
        return houseLv * popPer;
    }

    // ================================================================
    //  popUsed - 对应 JS Core.popUsed: sum(unit.pop * count)
    // ================================================================

    public int popUsed(Long playerId) {
        int sum = 0;
        List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        for (ArmyUnit unit : units) {
            UnitDef def = GameData.UNITS.get(unit.getType());
            if (def != null) {
                int count = unit.getCount() != null ? unit.getCount() : 0;
                sum += def.pop() * count;
            }
        }
        return sum;
    }

    // ================================================================
    //  popFree - 对应 JS Core.popFree: popMax - popUsed
    // ================================================================

    public int popFree(Long playerId) {
        return popMax(playerId) - popUsed(playerId);
    }

    // ================================================================
    //  Helper methods
    // ================================================================

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

    private int maxAffordableByResources(Resources resources, Map<String, Integer> unitCost) {
        if (resources == null) return 0;
        int limit = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : unitCost.entrySet()) {
            int perUnit = entry.getValue() != null ? entry.getValue() : 0;
            if (perUnit > 0) limit = (int) Math.min(limit, getResource(resources, entry.getKey()) / perUnit);
        }
        return limit == Integer.MAX_VALUE ? 0 : limit;
    }

    private boolean costEnough(Long playerId, Map<String, Integer> costs) {
        Resources r = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (r == null) return false;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            if ("pop".equals(entry.getKey())) continue;
            long have = getResource(r, entry.getKey());
            if (have < entry.getValue()) return false;
        }
        return true;
    }

    private void deductCosts(Long playerId, Map<String, Integer> costs) {
        Resources r = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (r == null) return;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            long current = getResource(r, entry.getKey());
            setResource(r, entry.getKey(), current - entry.getValue());
        }
        resourcesRepository.save(r);
    }

    private long getResource(Resources r, String key) {
        return switch (key) {
            case "food"  -> r.getFood()  != null ? r.getFood()  : 0;
            case "steel" -> r.getSteel() != null ? r.getSteel() : 0;
            case "oil"   -> r.getOil()   != null ? r.getOil()   : 0;
            case "rare"  -> r.getRare()  != null ? r.getRare()  : 0;
            case "gold"  -> r.getGold()  != null ? r.getGold()  : 0;
            default -> 0;
        };
    }

    private void setResource(Resources r, String key, long value) {
        switch (key) {
            case "food"  -> r.setFood(value);
            case "steel" -> r.setSteel(value);
            case "oil"   -> r.setOil(value);
            case "rare"  -> r.setRare(value);
            case "gold"  -> r.setGold(value);
            default -> {}
        }
    }
}
