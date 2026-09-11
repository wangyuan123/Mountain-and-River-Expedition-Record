package com.wargame.service;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.FortDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 城防工事服务 - 对应 JS 中 G.Fort 的后端实现。
 * <p>
 * 核心逻辑参考 js/fort.js：
 * <ul>
 *   <li>{@code G.Fort.build}      -> {@link #build}</li>
 *   <li>{@code G.Fort.dismantle}  -> {@link #dismantle}</li>
 *   <li>{@code fortCap()}          -> {@link #fortCap}</li>
 *   <li>{@code totalForts()}       -> {@link #totalForts}</li>
 * </ul>
 */
@Service
public class FortService {

    private final FortificationRepository fortificationRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;

    /** 城防上限系数 - 对应 JS: (s.buildings.wall || 0) * 800 */
    private static final int FORT_CAP_PER_WALL_LEVEL = 800;
    /** 拆除返还比例 - 对应 JS: Math.floor(cost.steel * n * 0.3) */
    private static final double DISMANTLE_REFUND_RATIO = 0.3;

    public FortService(FortificationRepository fortificationRepository,
                       ResourcesRepository resourcesRepository,
                       BuildingRepository buildingRepository) {
        this.fortificationRepository = fortificationRepository;
        this.resourcesRepository = resourcesRepository;
        this.buildingRepository = buildingRepository;
    }

    // ================================================================
    //  build - 对应 JS G.Fort.build
    // ================================================================

    @Transactional
    public Map<String, Object> build(Long playerId, String fortType, int count) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 校验城防类型 (JS: var f = D.forts[id])
        FortDef f = GameData.FORTS.get(fortType);
        if (f == null) {
            result.put("success", false);
            result.put("message", "无效的城防类型: " + fortType);
            return result;
        }

        // 2. 检查围墙等级 (JS: if ((s.buildings.wall || 0) < 1))
        int wallLv = buildingLevel(playerId, "wall");
        if (wallLv < 1) {
            result.put("success", false);
            result.put("message", "需先建造围墙才能修筑城防");
            return result;
        }

        // 3. 检查城防上限 (JS: if (totalForts() + n > fortCap()))
        int cap = fortCap(playerId);
        int total = totalForts(playerId);
        if (total + count > cap) {
            result.put("success", false);
            result.put("message", "城防数量已达上限(围墙Lv." + wallLv + " 上限" + cap + ")");
            return result;
        }

        // 4. 计算费用 (JS: var cost = fortCost(id, n) = f.cost[k] * n)
        Map<String, Integer> cost = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : f.cost().entrySet()) {
            cost.put(entry.getKey(), entry.getValue() * count);
        }

        // 5. 检查资源 (JS: if (!Core.costEnough(cost)))
        if (!costEnough(playerId, cost)) {
            result.put("success", false);
            result.put("message", "资源不足");
            return result;
        }

        // 6. 扣除资源 (JS: Core.payCost(cost))
        deductCosts(playerId, cost);

        // 7. 增加城防 (JS: s.forts[id] = (s.forts[id] || 0) + n)
        List<Fortification> existing = fortificationRepository.findByPlayerIdAndType(playerId, fortType);
        if (existing != null && !existing.isEmpty()) {
            Fortification fort = existing.get(0);
            fort.setCount((fort.getCount() != null ? fort.getCount() : 0) + count);
            fortificationRepository.save(fort);
        } else {
            Fortification fort = new Fortification();
            fort.setPlayerId(playerId);
            fort.setType(fortType);
            fort.setCount(count);
            fortificationRepository.save(fort);
        }

        result.put("success", true);
        result.put("message", "修筑 " + f.name() + " x" + count);
        result.put("fortType", fortType);
        result.put("count", count);
        return result;
    }

    // ================================================================
    //  dismantle - 对应 JS G.Fort.dismantle
    // ================================================================

    @Transactional
    public Map<String, Object> dismantle(Long playerId, String fortType, int count) {
        Map<String, Object> result = new LinkedHashMap<>();

        FortDef f = GameData.FORTS.get(fortType);
        if (f == null) {
            result.put("success", false);
            result.put("message", "无效的城防类型: " + fortType);
            return result;
        }

        // JS: var have = s.forts[id] || 0; if (have <= 0)
        List<Fortification> existing = fortificationRepository.findByPlayerIdAndType(playerId, fortType);
        int have = 0;
        Fortification fort = null;
        if (existing != null && !existing.isEmpty()) {
            fort = existing.get(0);
            have = fort.getCount() != null ? fort.getCount() : 0;
        }
        if (have <= 0) {
            result.put("success", false);
            result.put("message", "无该城防可拆除");
            return result;
        }

        // JS: n = Math.min(readQty(), have)
        int n = Math.min(count, have);

        // JS: s.forts[id] = have - n
        fort.setCount(have - n);
        fortificationRepository.save(fort);

        // JS: var back = Math.floor((D.forts[id].cost.steel || 0) * n * 0.3)
        int steelCost = f.cost().getOrDefault("steel", 0);
        int back = (int) Math.floor(steelCost * n * DISMANTLE_REFUND_RATIO);
        if (back > 0) {
            Resources res = resourcesRepository.findByPlayerId(playerId).orElse(null);
            if (res != null) {
                res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + back);
                resourcesRepository.save(res);
            }
        }

        result.put("success", true);
        result.put("message", "拆除 " + f.name() + " x" + n + " 回收钢" + back);
        result.put("count", n);
        result.put("refund", back);
        return result;
    }

    // ================================================================
    //  getForts - 返回所有城防
    // ================================================================

    public Map<String, Integer> getForts(Long playerId) {
        Map<String, Integer> forts = new LinkedHashMap<>();
        List<Fortification> list = fortificationRepository.findByPlayerId(playerId);
        for (Fortification fort : list) {
            int count = fort.getCount() != null ? fort.getCount() : 0;
            if (count > 0) {
                forts.put(fort.getType(), count);
            }
        }
        return forts;
    }

    // ================================================================
    //  fortCap - 对应 JS fortCap(): (s.buildings.wall || 0) * 800
    // ================================================================

    public int fortCap(Long playerId) {
        return buildingLevel(playerId, "wall") * FORT_CAP_PER_WALL_LEVEL;
    }

    // ================================================================
    //  totalForts - 对应 JS totalForts()
    // ================================================================

    public int totalForts(Long playerId) {
        int sum = 0;
        List<Fortification> list = fortificationRepository.findByPlayerId(playerId);
        for (Fortification fort : list) {
            sum += fort.getCount() != null ? fort.getCount() : 0;
        }
        return sum;
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

    private boolean costEnough(Long playerId, Map<String, Integer> costs) {
        Resources r = resourcesRepository.findByPlayerId(playerId).orElse(null);
        if (r == null) return false;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
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
