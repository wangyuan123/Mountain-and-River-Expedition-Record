package com.wargame.service;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.TechDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 科技研究服务 - 对应 JS 中 G.Tech 的后端实现。
 * <p>
 * 支持科研工期、研究队列、倒计时自动结算、加速道具及取消退费。
 */
@Service
public class TechService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    private final TechnologyRepository technologyRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;
    private final PlayerRepository playerRepository;
    private final TechResearchQueueRepository techResearchQueueRepository;
    private final SpeedUpSupport speedUpSupport;

    /** 科技效果每级百分比 - 对应 JS tech.js renderView 中的 pctMap */
    private static final Map<String, Integer> PCT_MAP = Map.of(
            "cap", 10,
            "load", 20,
            "food_save", -5,
            "train", 10,
            "build", -5,
            "medical", 5
    );
    private static final int DEFAULT_PCT = 5;

    public TechService(TechnologyRepository technologyRepository,
                       ResourcesRepository resourcesRepository,
                       BuildingRepository buildingRepository,
                       PlayerRepository playerRepository) {
        this(technologyRepository, resourcesRepository, buildingRepository, playerRepository, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TechService(TechnologyRepository technologyRepository,
                       ResourcesRepository resourcesRepository,
                       BuildingRepository buildingRepository,
                       PlayerRepository playerRepository,
                       TechResearchQueueRepository techResearchQueueRepository,
                       SpeedUpSupport speedUpSupport) {
        this.technologyRepository = technologyRepository;
        this.resourcesRepository = resourcesRepository;
        this.buildingRepository = buildingRepository;
        this.playerRepository = playerRepository;
        this.techResearchQueueRepository = techResearchQueueRepository;
        this.speedUpSupport = speedUpSupport;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.repository.OfficerRepository officerRepository;

    // ================================================================
    //  upgrade / startResearch - 开始研发科技
    // ================================================================

    @Transactional
    public Map<String, Object> upgrade(Long playerId, String techType) {
        return startResearch(playerId, techType);
    }

    @Transactional
    public Map<String, Object> startResearch(Long playerId, String techType) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 校验科技类型
        TechDef t = GameData.TECHS.get(techType);
        if (t == null) {
            result.put("success", false);
            result.put("message", "无效的科技类型: " + techType);
            return result;
        }

        // 2. 先结算可能已到期的科研
        long now = System.currentTimeMillis();
        settleCompletedResearch(playerId, now);

        // 检查是否有正在研究的科技（科技为全帝国共享，同时只能研发一项）
        List<TechResearchQueue> existingQueue = techResearchQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId);
        if (!existingQueue.isEmpty()) {
            TechResearchQueue current = existingQueue.get(0);
            TechDef curDef = GameData.TECHS.get(current.getTechType());
            String curName = curDef != null ? curDef.name() : current.getTechType();
            result.put("success", false);
            result.put("message", "科研中心正在研发【" + curName + " Lv." + current.getTargetLevel() + "】，请等待完成或使用加速符");
            return result;
        }

        // 3. 检查科研中心等级 (JS: labLv < t.labReq)
        int labLv = buildingLevel(playerId, "lab");
        if (labLv < t.labReq()) {
            result.put("success", false);
            result.put("message", "需科研中心 Lv." + t.labReq());
            return result;
        }

        // 4. 检查是否满级 (JS: lv >= t.max)
        int lv = getTechLevel(playerId, techType);
        if (lv >= t.maxLevel()) {
            result.put("success", false);
            result.put("message", "已满级");
            return result;
        }

        // 5. 计算费用与工期 (JS: techCost - baseCost * growth^lv)
        Map<String, Integer> cost = calcTechCost(techType, lv);
        int duration = calcTechDuration(playerId, techType, lv, labLv);

        // 6. 检查资源是否充足 (JS: Core.costEnough)
        if (!costEnough(playerId, cost)) {
            result.put("success", false);
            result.put("message", "资源不足");
            return result;
        }

        // 7. 扣除资源 (JS: Core.payCost)
        deductCosts(playerId, cost);

        // 8. 创建研发队列
        TechResearchQueue queue = new TechResearchQueue();
        queue.setPlayerId(playerId);
        queue.setCitySlot(cityScope.slot(playerId));
        queue.setTechType(techType);
        queue.setTargetLevel(lv + 1);
        queue.setStartedAt(now);
        queue.setFinishesAt(now + duration * 1000L);
        queue.setDurationSeconds(duration);
        queue.setCostFood(cost.getOrDefault("food", 0));
        queue.setCostSteel(cost.getOrDefault("steel", 0));
        queue.setCostOil(cost.getOrDefault("oil", 0));
        queue.setCostRare(cost.getOrDefault("rare", 0));
        queue.setCostGold(cost.getOrDefault("gold", 0));
        techResearchQueueRepository.save(queue);

        result.put("success", true);
        result.put("message", "已开始研发【" + t.name() + " Lv." + (lv + 1) + "】，预计 " + duration + " 秒");
        result.put("techType", techType);
        result.put("targetLevel", lv + 1);
        result.put("durationSeconds", duration);
        result.put("startedAt", now);
        result.put("finishesAt", queue.getFinishesAt());
        result.put("queueId", queue.getId());
        result.put("cost", cost);

        return result;
    }

    // ================================================================
    //  cancelResearch - 取消研发，返还 80% 资源
    // ================================================================

    @Transactional
    public Map<String, Object> cancelResearch(Long playerId, Long queueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        TechResearchQueue q = null;
        if (queueId != null) {
            q = techResearchQueueRepository.findById(queueId).orElse(null);
        } else {
            List<TechResearchQueue> list = techResearchQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId);
            if (!list.isEmpty()) q = list.get(0);
        }

        if (q == null || !playerId.equals(q.getPlayerId())) {
            result.put("success", false);
            result.put("message", "未找到正在进行的科技研发");
            return result;
        }

        // 返还 80% 资源
        int refundFood = (int) Math.floor(q.getCostFood() * 0.8);
        int refundSteel = (int) Math.floor(q.getCostSteel() * 0.8);
        int refundOil = (int) Math.floor(q.getCostOil() * 0.8);
        int refundRare = (int) Math.floor(q.getCostRare() * 0.8);
        int refundGold = (int) Math.floor(q.getCostGold() * 0.8);

        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, q.getCitySlot() != null ? q.getCitySlot() : 0).orElse(null);
        if (res != null) {
            res.setFood((res.getFood() != null ? res.getFood() : 0) + refundFood);
            res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + refundSteel);
            res.setOil((res.getOil() != null ? res.getOil() : 0) + refundOil);
            res.setRare((res.getRare() != null ? res.getRare() : 0) + refundRare);
            res.setGold((res.getGold() != null ? res.getGold() : 0) + refundGold);
            resourcesRepository.save(res);
        }

        techResearchQueueRepository.delete(q);
        TechDef t = GameData.TECHS.get(q.getTechType());
        String name = t != null ? t.name() : q.getTechType();

        result.put("success", true);
        result.put("message", "已取消【" + name + "】研发，返还80%消耗资源");
        return result;
    }

    // ================================================================
    //  useSpeedUp - 使用加速符加速科技研发
    // ================================================================

    @Transactional
    public Map<String, Object> useSpeedUp(Long playerId, Long queueId, String itemId, int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();

        TechResearchQueue target = null;
        if (queueId != null) {
            target = techResearchQueueRepository.findById(queueId).orElse(null);
        } else {
            List<TechResearchQueue> list = techResearchQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId);
            if (!list.isEmpty()) target = list.get(0);
        }

        if (target == null || !playerId.equals(target.getPlayerId())) {
            result.put("success", false);
            result.put("message", "当前没有正在进行的科技研发");
            return result;
        }

        ItemDef item = speedUpSupport.consume(playerId, itemId, count, now, result);
        if (item == null) return result;

        long reduceMs = item.speedUpSeconds() * 1000L * count;
        long newFinish = Math.max(now, target.getFinishesAt() - reduceMs);
        target.setFinishesAt(newFinish);
        techResearchQueueRepository.save(target);

        speedUpSupport.cleanupZeroCount(playerId, itemId);

        TechDef t = GameData.TECHS.get(target.getTechType());
        String name = t != null ? t.name() : target.getTechType();

        if (newFinish <= now) {
            settleCompletedResearch(playerId, now);
            result.put("success", true);
            result.put("message", "⚡ 加速成功！【" + name + "】研发已完成！");
            result.put("completed", true);
        } else {
            long remaining = (newFinish - now) / 1000L;
            result.put("success", true);
            result.put("message", "⚡ 加速成功，剩余研发时间: " + formatDuration(remaining));
            result.put("completed", false);
            result.put("finishesAt", newFinish);
            result.put("remainingSeconds", remaining);
        }
        return result;
    }

    // ================================================================
    //  settleCompletedResearch - 结算到期科技
    // ================================================================

    @Transactional
    public List<String> settleCompletedResearch(Long playerId, long now) {
        List<TechResearchQueue> completed = techResearchQueueRepository
                .findByPlayerIdAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(playerId, now);
        if (completed == null || completed.isEmpty()) return Collections.emptyList();

        List<String> messages = new ArrayList<>();
        for (TechResearchQueue q : completed) {
            String techType = q.getTechType();
            int targetLevel = q.getTargetLevel();
            TechDef t = GameData.TECHS.get(techType);
            String name = t != null ? t.name() : techType;

            // 升级科技等级
            List<Technology> existing = technologyRepository.findByPlayerIdAndType(playerId, techType);
            if (existing != null && !existing.isEmpty()) {
                Technology tech = existing.get(0);
                tech.setLevel(targetLevel);
                technologyRepository.save(tech);
            } else {
                Technology tech = new Technology();
                tech.setPlayerId(playerId);
                tech.setType(techType);
                tech.setLevel(targetLevel);
                technologyRepository.save(tech);
            }

            // 增加声望 (totalCost/100)
            int totalCost = q.getCostFood() + q.getCostSteel() + q.getCostOil() + q.getCostRare() + q.getCostGold();
            int prestigeGain = totalCost > 0 ? Math.max(1, totalCost / 100) : 0;
            if (prestigeGain > 0) {
                Player player = playerRepository.findById(playerId).orElse(null);
                if (player != null) {
                    player.setPrestige((player.getPrestige() != null ? player.getPrestige() : 0) + prestigeGain);
                    playerRepository.save(player);
                }
            }

            techResearchQueueRepository.delete(q);
            messages.add("🔬 科技【" + name + "】已成功研发至 Lv." + targetLevel + "！");
        }
        return messages;
    }

    // ================================================================
    //  getActiveResearch - 查询当前玩家进行中的科研任务
    // ================================================================

    public Map<String, Object> getActiveResearch(Long playerId) {
        List<TechResearchQueue> list = techResearchQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId);
        if (list == null || list.isEmpty()) return null;
        TechResearchQueue q = list.get(0);
        long now = System.currentTimeMillis();
        long rem = Math.max(0, (q.getFinishesAt() - now) / 1000L);
        TechDef t = GameData.TECHS.get(q.getTechType());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queueId", q.getId());
        m.put("techType", q.getTechType());
        m.put("name", t != null ? t.name() : q.getTechType());
        m.put("targetLevel", q.getTargetLevel());
        m.put("startedAt", q.getStartedAt());
        m.put("finishesAt", q.getFinishesAt());
        m.put("durationSeconds", q.getDurationSeconds());
        m.put("remainingSeconds", rem);
        m.put("citySlot", q.getCitySlot());
        return m;
    }

    // ================================================================
    //  getTechMap - 查询所有科技等级
    // ================================================================

    public Map<String, Object> getTechMap(Long playerId) {
        List<Technology> techs = technologyRepository.findByPlayerId(playerId);
        Map<String, Object> techMap = new LinkedHashMap<>();
        for (Technology tech : techs) {
            if (!TechDef.TECHS.containsKey(tech.getType())) continue;
            techMap.put(tech.getType(), tech.getLevel());
        }
        return techMap;
    }

    // ================================================================
    //  getTechLevel - 获取指定科技当前等级
    // ================================================================

    public int getTechLevel(Long playerId, String techType) {
        List<Technology> techs = technologyRepository.findByPlayerIdAndType(playerId, techType);
        if (techs == null || techs.isEmpty()) return 0;
        return techs.get(0).getLevel() != null ? techs.get(0).getLevel() : 0;
    }

    // ================================================================
    //  calcTechDuration - 计算研发工期（秒，受科研中心等级及市长格物技能加速）
    // ================================================================

    public int calcTechDuration(Long playerId, String techType, int currentLevel, int labLevel) {
        double base = 30.0 * Math.pow(1.8, Math.max(0, currentLevel));
        double labSpeed = 1.0 + 0.10 * Math.max(0, labLevel - 1);
        int researchLv = getMayorResearchLevel(playerId);
        double mayorSpeed = 1.0 + 0.08 * researchLv;
        int duration = (int) Math.round(base / (labSpeed * mayorSpeed));
        return Math.max(5, Math.min(86400, duration));
    }

    public int calcTechDuration(String techType, int currentLevel, int labLevel) {
        return calcTechDuration(null, techType, currentLevel, labLevel);
    }

    private int getMayorResearchLevel(Long playerId) {
        if (officerRepository == null || cityScope == null || playerId == null) return 0;
        try {
            List<Officer> list = officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, cityScope.slot(playerId), "mayor");
            if (list == null || list.isEmpty()) return 0;
            Officer mayor = list.get(0);
            if (mayor.getSkills() == null || mayor.getSkills().isBlank()) return 0;
            List<Map<String, Object>> skills = JsonUtil.parseList(mayor.getSkills());
            for (Map<String, Object> sk : skills) {
                if ("research".equals(sk.get("id"))) {
                    Object lv = sk.get("lv");
                    return lv instanceof Number ? ((Number) lv).intValue() : 0;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ================================================================
    //  calcTechBonus - 计算累积科技加成
    // ================================================================

    public int calcTechBonus(Long playerId, String bonusType) {
        int total = 0;
        for (Map.Entry<String, TechDef> entry : GameData.TECHS.entrySet()) {
            if (bonusType.equals(entry.getValue().effect())) {
                int lv = getTechLevel(playerId, entry.getKey());
                int pct = PCT_MAP.getOrDefault(bonusType, DEFAULT_PCT);
                total += lv * pct;
            }
        }
        return total;
    }

    // ================================================================
    //  calcTechCost - 对应 JS techCost
    // ================================================================

    public Map<String, Integer> calcTechCost(String techType, int currentLevel) {
        TechDef t = GameData.TECHS.get(techType);
        if (t == null) return Collections.emptyMap();

        Map<String, Integer> cost = new LinkedHashMap<>();
        double mul = Math.pow(t.costGrowth(), currentLevel);
        for (Map.Entry<String, Integer> entry : t.cost().entrySet()) {
            cost.put(entry.getKey(), (int) Math.floor(entry.getValue() * mul));
        }
        return cost;
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

    private boolean costEnough(Long playerId, Map<String, Integer> costs) {
        Resources r = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (r == null) return false;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            int have = getResource(r, entry.getKey());
            if (have < entry.getValue()) return false;
        }
        return true;
    }

    private void deductCosts(Long playerId, Map<String, Integer> costs) {
        Resources r = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
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

    private static String formatDuration(long seconds) {
        if (seconds >= 3600) {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            long s = seconds % 60;
            return String.format("%02d:%02d:%02d", h, m, s);
        } else {
            long m = seconds / 60;
            long s = seconds % 60;
            return String.format("%02d:%02d", m, s);
        }
    }
}
