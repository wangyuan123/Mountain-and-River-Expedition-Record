package com.wargame.service;

import com.wargame.model.constants.GameConstants;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.BuildingDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
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

        completeProduction(playerId, System.currentTimeMillis());

        // 2. 列出所有该兵种对应的训练建筑 - 方案B: 按栋独立计算后求和
        //    不再使用"sum(blv)"的全局求和公式，改为遍历每栋兵工厂：
        //      maxBatch_f = level_f × 10 × trainMul
        //      parallel_f = 1 + level_f / 5
        //      speed_f    = 1 + min(4, level_f × 0.05) + (trainMul - 1)
        List<Building> factoryBuildings = buildingRepository.findByPlayerIdAndType(playerId, u.build());
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
        Player player = playerRepository.findById(playerId).orElse(null);
        int civilians = player != null && player.getCivilianPopulation() != null ? player.getCivilianPopulation() : 0;
        Resources resources = resourcesRepository.findByPlayerId(playerId).orElse(null);
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
            player.setCivilianPopulation(civilians - popNeeded);
            if (prestigeGain > 0) {
                player.setPrestige((player.getPrestige() != null ? player.getPrestige() : 0) + prestigeGain);
            }
            playerRepository.save(player);
        }

        long now = System.currentTimeMillis();
        // 方案B: parallel 与 avgSpeed 已在上方按栋独立计算后求和得到, 这里直接使用
        int baseSeconds = 30 + u.cost().values().stream().mapToInt(Integer::intValue).sum() / 10;
        int duration = Math.max(5, (int) Math.ceil(baseSeconds * n / avgSpeed));
        long startsAt = productionStartsAt(playerId, u.build(), parallel, now);
        ArmyProductionQueue queue = new ArmyProductionQueue();
        queue.setPlayerId(playerId);
        queue.setUnitType(unitType);
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
        result.put("message", "已加入生产队列 " + u.name() + " x" + n + "，预计 " + duration + " 秒");
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
        Map<String, Object> result = new LinkedHashMap<>();

        UnitDef u = GameData.UNITS.get(unitType);
        if (u == null) {
            result.put("success", false);
            result.put("message", "无效的兵种: " + unitType);
            return result;
        }

        // JS: var have = s.army[id] || 0; if (have <= 0)
        List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndType(playerId, unitType);
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
            int civilians = player.getCivilianPopulation() != null ? player.getCivilianPopulation() : 0;
            player.setCivilianPopulation(Math.min(popMax(playerId), civilians + u.pop() * n));
            playerRepository.save(player);
        }

        // JS: s.resources.steel += Math.floor(D.units[id].cost.steel * n * 0.3)
        int steelCost = u.cost().getOrDefault("steel", 0);
        int refund = (int) Math.floor(steelCost * n * 0.3);
        if (refund > 0) {
            Resources res = resourcesRepository.findByPlayerId(playerId).orElse(null);
            if (res != null) {
                res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + refund);
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
        completeProduction(playerId, System.currentTimeMillis());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ArmyProductionQueue q : armyProductionQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", q.getId()); item.put("unitType", q.getUnitType()); item.put("count", q.getUnitCount());
            item.put("startedAt", q.getStartedAt()); item.put("finishesAt", q.getFinishesAt());
            item.put("durationSeconds", q.getDurationSeconds());
            item.put("remainingSeconds", Math.max(0, (q.getFinishesAt() - System.currentTimeMillis() + 999) / 1000));
            result.add(item);
        }
        return result;
    }

    @Transactional
    public boolean cancelProduction(Long playerId, Long queueId) {
        ArmyProductionQueue q = armyProductionQueueRepository.findById(queueId).orElse(null);
        if (q == null || !playerId.equals(q.getPlayerId())) return false;
        armyProductionQueueRepository.delete(q);
        refund(playerId, q.getCostFood(), q.getCostSteel(), q.getCostOil(), q.getCostRare());
        UnitDef unit = GameData.UNITS.get(q.getUnitType());
        Player player = playerRepository.findById(playerId).orElse(null);
        if (unit != null && player != null) {
            int civilians = player.getCivilianPopulation() != null ? player.getCivilianPopulation() : 0;
            player.setCivilianPopulation(Math.min(popMax(playerId), civilians + unit.pop() * q.getUnitCount()));
            playerRepository.save(player);
        }
        return true;
    }

    @Transactional
    public void completeProduction(Long playerId, long now) {
        for (ArmyProductionQueue q : armyProductionQueueRepository.findByPlayerIdAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(playerId, now)) {
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndType(playerId, q.getUnitType());
            ArmyUnit unit = existing.isEmpty() ? new ArmyUnit(null, playerId, q.getUnitType(), 0) : existing.get(0);
            unit.setCount((unit.getCount() == null ? 0 : unit.getCount()) + q.getUnitCount());
            armyUnitRepository.save(unit);
            armyProductionQueueRepository.delete(q);
            try { questService.onEvent(playerId, "ARMY_RECRUIT", q.getUnitType(), q.getUnitCount()); questService.onEvent(playerId, "ARMY_TOTAL"); } catch (Exception ignored) {}
        }
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
        long now = System.currentTimeMillis();
        if (count <= 0) count = 1;

        // 1. 校验道具并原子扣减 count 个
        com.wargame.model.constants.ItemDef item = speedUpSupport.consume(playerId, itemId, count, now, result);
        if (item == null) return result;
        long reduceMs = item.speedUpSeconds() * 1000L * count;

        // 2. 找到目标生产订单
        ArmyProductionQueue target = null;
        if (queueId != null) {
            target = armyProductionQueueRepository.findById(queueId).orElse(null);
            if (target == null || !playerId.equals(target.getPlayerId())) {
                speedUpSupport.refund(playerId, itemId, count, now);
                result.put("success", false);
                result.put("message", "指定的生产任务不存在");
                return result;
            }
        } else {
            // 兼容旧版未传 queueId 时, 取最早一个未完成的订单
            for (ArmyProductionQueue q : armyProductionQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId)) {
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
        if (completed) {
            target.setFinishesAt(now);
            armyProductionQueueRepository.save(target);
            completeProduction(playerId, now);
        } else {
            target.setFinishesAt(newFinishAt);
            armyProductionQueueRepository.save(target);
        }

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
            result.put("message", "⚡ " + item.name() + " ×" + count + " 使用成功，剩余 " + remainSec + " 秒");
            result.put("remainingSeconds", remainSec);
        }
        return result;
    }

    private long productionStartsAt(Long playerId, String buildingType, int parallel, long now) {
        List<Long> laneAvailableAt = new ArrayList<>();
        for (int i = 0; i < parallel; i++) laneAvailableAt.add(now);
        for (ArmyProductionQueue q : armyProductionQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId)) {
            UnitDef queuedUnit = GameData.UNITS.get(q.getUnitType());
            if (queuedUnit == null || !buildingType.equals(queuedUnit.build())) continue;
            int lane = 0;
            for (int i = 1; i < laneAvailableAt.size(); i++) if (laneAvailableAt.get(i) < laneAvailableAt.get(lane)) lane = i;
            laneAvailableAt.set(lane, Math.max(laneAvailableAt.get(lane), q.getFinishesAt()));
        }
        return Collections.min(laneAvailableAt);
    }

    private int queuedArmy(Long playerId) {
        return armyProductionQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).stream().mapToInt(q -> q.getUnitCount() == null ? 0 : q.getUnitCount()).sum();
    }

    private int queuedPopulation(Long playerId) {
        return armyProductionQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).stream()
                .mapToInt(q -> { UnitDef unit = GameData.UNITS.get(q.getUnitType()); return unit == null ? 0 : unit.pop() * q.getUnitCount(); }).sum();
    }

    private void refund(Long playerId, int food, int steel, int oil, int rare) {
        Resources r = resourcesRepository.findByPlayerId(playerId).orElse(null); if (r == null) return;
        r.setFood((r.getFood() == null ? 0 : r.getFood()) + food); r.setSteel((r.getSteel() == null ? 0 : r.getSteel()) + steel);
        r.setOil((r.getOil() == null ? 0 : r.getOil()) + oil); r.setRare((r.getRare() == null ? 0 : r.getRare()) + rare); resourcesRepository.save(r);
    }

    public Map<String, Integer> getArmy(Long playerId) {
        completeProduction(playerId, System.currentTimeMillis());
        Map<String, Integer> army = new LinkedHashMap<>();
        List<ArmyUnit> units = armyUnitRepository.findByPlayerId(playerId);
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
        List<ArmyUnit> units = armyUnitRepository.findByPlayerId(playerId);
        for (ArmyUnit unit : units) {
            sum += unit.getCount() != null ? unit.getCount() : 0;
        }
        return sum;
    }

    // ================================================================
    //  trainMul - 对应 JS Core.trainMul: 1 + 0.10 * log_train
    // ================================================================

    public double trainMul(Long playerId) {
        int logTrain = getTechLevel(playerId, "log_train");
        return 1 + 0.10 * logTrain;
    }

    // ================================================================
    //  armyCap - 对应 JS Core.armyCap
    //  base = rankBase (军衔基础: 1,000~20,000) + commandLv * 1,000 (市政厅: 1,000~10,000)
    //  满级基础 = 20,000 + 10,000 = 30,000
    //  return floor(base * (1 + staffLv * 0.10) * (1 + cmdLv * 0.025))
    //  满级 (上将 + 市政厅10 + 参谋部10 + 指挥官100): 30,000 * 2.0 * 3.5 = 210,000
    // ================================================================

    public int armyCap(Long playerId) {
        Player player = playerRepository.findById(playerId).orElse(null);
        int rankTier = (player != null && player.getMilitaryRank() != null) ? player.getMilitaryRank() : 1;
        int rankBase = MilitaryRankDef.getRankBase(rankTier);

        int commandLv = buildingLevel(playerId, "command");
        if (commandLv < 1) commandLv = 1;
        int staffLv = buildingLevel(playerId, "staff");

        // Commander level
        int cmdLv = 1;
        List<Officer> commanders = officerRepository.findByPlayerIdAndRole(playerId, "commander");
        if (commanders != null && !commanders.isEmpty()) {
            cmdLv = commanders.get(0).getLevel() != null ? commanders.get(0).getLevel() : 1;
        }

        int base = rankBase + commandLv * 1000;
        return (int) Math.floor(base * (1 + staffLv * 0.10) * (1 + cmdLv * 0.025));
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
        List<ArmyUnit> units = armyUnitRepository.findByPlayerId(playerId);
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
        List<Building> buildings = buildingRepository.findByPlayerIdAndType(playerId, buildingType);
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
            if (perUnit > 0) limit = Math.min(limit, getResource(resources, entry.getKey()) / perUnit);
        }
        return limit == Integer.MAX_VALUE ? 0 : limit;
    }

    private boolean costEnough(Long playerId, Map<String, Integer> costs) {
        Resources r = resourcesRepository.findByPlayerId(playerId).orElse(null);
        if (r == null) return false;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            if ("pop".equals(entry.getKey())) continue;
            int have = getResource(r, entry.getKey());
            if (have < entry.getValue()) return false;
        }
        return true;
    }

    private void deductCosts(Long playerId, Map<String, Integer> costs) {
        Resources r = resourcesRepository.findByPlayerId(playerId).orElse(null);
        if (r == null) return;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            int current = getResource(r, entry.getKey());
            setResource(r, entry.getKey(), current - entry.getValue());
        }
        resourcesRepository.save(r);
    }

    private int getResource(Resources r, String key) {
        return switch (key) {
            case "food"  -> r.getFood()  != null ? r.getFood()  : 0;
            case "steel" -> r.getSteel() != null ? r.getSteel() : 0;
            case "oil"   -> r.getOil()   != null ? r.getOil()   : 0;
            case "rare"  -> r.getRare()  != null ? r.getRare()  : 0;
            case "gold"  -> r.getGold()  != null ? r.getGold()  : 0;
            default -> 0;
        };
    }

    private void setResource(Resources r, String key, int value) {
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
