package com.wargame.service;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.TechDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 科技研究服务 - 对应 JS 中 G.Tech 的后端实现。
 * <p>
 * 核心逻辑参考 js/tech.js：
 * <ul>
 *   <li>{@code G.Tech.research}     -> {@link #upgrade}</li>
 *   <li>{@code G.techCost}          -> {@link #calcTechCost}</li>
 *   <li>科技加成计算                 -> {@link #calcTechBonus}</li>
 * </ul>
 */
@Service
public class TechService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    private final TechnologyRepository technologyRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;
    private final PlayerRepository playerRepository;

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
        this.technologyRepository = technologyRepository;
        this.resourcesRepository = resourcesRepository;
        this.buildingRepository = buildingRepository;
        this.playerRepository = playerRepository;
    }

    // ================================================================
    //  upgrade - 对应 JS G.Tech.research
    // ================================================================

    @Transactional
    public Map<String, Object> upgrade(Long playerId, String techType) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 校验科技类型
        TechDef t = GameData.TECHS.get(techType);
        if (t == null) {
            result.put("success", false);
            result.put("message", "无效的科技类型: " + techType);
            return result;
        }

        // 2. 检查科研中心等级 (JS: labLv < t.labReq)
        int labLv = buildingLevel(playerId, "lab");
        if (labLv < t.labReq()) {
            result.put("success", false);
            result.put("message", "需科研中心 Lv." + t.labReq());
            return result;
        }

        // 3. 检查是否满级 (JS: lv >= t.max)
        int lv = getTechLevel(playerId, techType);
        if (lv >= t.maxLevel()) {
            result.put("success", false);
            result.put("message", "已满级");
            return result;
        }

        // 4. 计算费用 (JS: techCost - baseCost * growth^lv)
        Map<String, Integer> cost = calcTechCost(techType, lv);

        // 5. 检查资源是否充足 (JS: Core.costEnough)
        if (!costEnough(playerId, cost)) {
            result.put("success", false);
            result.put("message", "资源不足");
            return result;
        }

        // 6. 扣除资源 (JS: Core.payCost)
        deductCosts(playerId, cost);

        // 7. 增加声望 (JS: Core.addPrestige - totalCost/100, min 1)
        int totalCost = 0;
        for (int v : cost.values()) totalCost += v;
        int prestigeGain = totalCost > 0 ? Math.max(1, totalCost / 100) : 0;
        if (prestigeGain > 0) {
            Player player = playerRepository.findById(playerId).orElse(null);
            if (player != null) {
                player.setPrestige((player.getPrestige() != null ? player.getPrestige() : 0) + prestigeGain);
                playerRepository.save(player);
            }
        }

        // 8. 升级科技等级 (JS: s.tech[id] = lv + 1)
        List<Technology> existing = technologyRepository.findByPlayerIdAndType(playerId, techType);
        if (existing != null && !existing.isEmpty()) {
            Technology tech = existing.get(0);
            tech.setLevel(lv + 1);
            technologyRepository.save(tech);
        } else {
            Technology tech = new Technology();
            tech.setPlayerId(playerId);
            tech.setType(techType);
            tech.setLevel(lv + 1);
            technologyRepository.save(tech);
        }

        result.put("success", true);
        result.put("message", t.name() + " 升至 Lv." + (lv + 1) + "，声望 +" + prestigeGain);
        result.put("techType", techType);
        result.put("level", lv + 1);
        result.put("cost", cost);
        result.put("prestigeGain", prestigeGain);

        return result;
    }

    // ================================================================
    //  getTechLevel - 获取当前科技等级
    // ================================================================

    public int getTechLevel(Long playerId, String techType) {
        List<Technology> techs = technologyRepository.findByPlayerIdAndType(playerId, techType);
        if (techs == null || techs.isEmpty()) return 0;
        return techs.get(0).getLevel() != null ? techs.get(0).getLevel() : 0;
    }

    // ================================================================
    //  calcTechBonus - 计算累积科技加成
    //  对应 JS tech.js renderView 中 cur = lv * pct 的逻辑
    // ================================================================

    /**
     * 计算指定效果类型的累积科技加成。
     * <p>
     * 遍历所有科技，找到 effect 匹配 bonusType 的科技，
     * 返回 sum(level * pct)。
     * <p>
     * pct 映射 (来自 JS pctMap):
     * <ul>
     *   <li>cap: 10%/级</li>
     *   <li>load: 20%/级</li>
     *   <li>food_save: -5%/级</li>
     *   <li>train: 10%/级</li>
     *   <li>build: -5%/级</li>
     *   <li>medical: 5%/级</li>
     *   <li>其他: 5%/级 (默认)</li>
     * </ul>
     *
     * @param playerId  玩家 ID
     * @param bonusType 效果类型 (对应 TechDef.effect)
     * @return 累积加成值
     */
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
    //  cost[k] = floor(baseCost[k] * growth^level)
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
}
