package com.wargame.service;

import com.wargame.model.constants.GameConstants;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 建筑升级与施工队列服务 - 对应 JS 中 G.Build 的后端实现。
 * <p>
 * 核心逻辑参考 js/build.js 和 js/core.js：
 * <ul>
 *   <li>{@code G.Build.upgrade}        -> {@link #upgrade}</li>
 *   <li>{@code G.Build.cancel}         -> {@link #cancel}（JS 中无此方法，按 50% 退款实现）</li>
 *   <li>{@code G.Build.completeUpgrade}-> {@link #completeUpgrade}</li>
 *   <li>{@code G.buildCost}            -> {@link #calcBuildCost}</li>
 *   <li>{@code G.buildDuration}        -> {@link #calcBuildTime}</li>
 *   <li>{@code G.buildMaxLevel}        -> {@link #maxBuildingLevel}</li>
 *   <li>{@code Core.buildingLevel}     -> {@link #buildingLevel}</li>
 *   <li>{@code Core.costEnough}        -> {@link #costEnough}</li>
 *   <li>{@code Core.payCost}           -> {@link #deductCosts}</li>
 * </ul>
 */
@Service
public class BuildService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;
    @org.springframework.beans.factory.annotation.Autowired
    private WorldTerrainService terrain;

    private final BuildingRepository buildingRepository;
    private final ConstructionRepository constructionRepository;
    private final ResourcesRepository resourcesRepository;
    private final TechnologyRepository technologyRepository;
    private final PlayerRepository playerRepository;
    private final PlayerItemRepository playerItemRepository;
    private final SpeedUpSupport speedUpSupport;
    private final com.wargame.service.quest.QuestService questService;

    // ===== 常量（与 JS / GameStateService 保持一致）=====

    /** 多槽位建筑：同一类型可建造多栋 */
    private static final Set<String> MULTI_SLOT = Set.of(
            "house", "farm", "refinery", "oilfield", "raremine", "factory", "depot"
    );

    /** 可在无市政厅时建造的基础建筑 */
    private static final Set<String> BASIC_BUILDINGS = Set.of(
            "command", "house", "farm", "refinery", "oilfield", "raremine"
    );

    /** 建筑分组 - 对应 JS G.Build.GROUPS */
    private static final Map<String, List<String>> GROUPS = Map.of(
            "res",  List.of("farm", "refinery", "oilfield", "raremine"),
            "army", List.of("command", "house", "factory", "lightfactory", "heavyfactory", "port",
                            "academy", "staff", "lab", "radar", "wall", "apron", "liaison", "depot", "transit", "exchange")
    );

    /** 同时施工上限 - JS: if (jobs.length >= 2) */
    private static final int MAX_CONCURRENT = 2;

    /** 取消施工退款比例（JS 中无 cancel，采用 50%） */
    private static final double CANCEL_REFUND_RATIO = 0.5;

    /** 拆除建筑退款比例（按 30% 回收） */
    private static final double DISMANTLE_REFUND_RATIO = 0.3;

    // ===== 构造器 =====

    public BuildService(BuildingRepository buildingRepository,
                        ConstructionRepository constructionRepository,
                        ResourcesRepository resourcesRepository,
                        TechnologyRepository technologyRepository,
                        PlayerRepository playerRepository,
                        PlayerItemRepository playerItemRepository,
                        SpeedUpSupport speedUpSupport,
                        com.wargame.service.quest.QuestService questService) {
        this.buildingRepository = buildingRepository;
        this.constructionRepository = constructionRepository;
        this.resourcesRepository = resourcesRepository;
        this.technologyRepository = technologyRepository;
        this.playerRepository = playerRepository;
        this.playerItemRepository = playerItemRepository;
        this.speedUpSupport = speedUpSupport;
        this.questService = questService;
    }

    // ================================================================
    //  upgrade - 对应 JS G.Build.upgrade
    // ================================================================

    @Transactional
    public Map<String, Object> upgrade(Long playerId, String buildingType, int slot) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 校验建筑类型
        BuildingDef b = GameData.BUILDINGS.get(buildingType);
        if (b == null) {
            result.put("success", false);
            result.put("message", "无效的建筑类型: " + buildingType);
            return result;
        }

        // 2. 检查施工队数量上限（JS: if (jobs.length >= 2)）
        List<Construction> allJobs = constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        if (allJobs.size() >= MAX_CONCURRENT) {
            result.put("success", false);
            result.put("message", "两支施工队都在忙，请等待完成");
            return result;
        }

        boolean multi = isMultiSlot(buildingType);
        Integer slotKey = multi ? slot : null;

        // 3. 获取当前等级
        int curLv;
        if (multi) {
            curLv = buildingLevel(playerId, buildingType, slot);
        } else {
            curLv = buildingLevel(playerId, buildingType);
        }

        if("port".equals(buildingType)&&curLv==0){
            terrain.ensure();
            var city=cityScope.selected(playerId);
            if(city.isPresent()&&!terrain.canUsePort(city.get())){
                result.put("success",false);result.put("message","新建港口需要沿海城市，请在海边建立分城");return result;
            }
        }

        // 4. 检查该建筑/槽位是否正在施工（JS: isBuilding 检查）
        for (Construction c : allJobs) {
            if (!buildingType.equals(c.getBuildingType())) continue;
            if (slotKey == null) {
                if (c.getSlot() == null) {
                    result.put("success", false);
                    result.put("message", b.name() + " 正在升级");
                    return result;
                }
            } else if (slotKey.equals(c.getSlot())) {
                result.put("success", false);
                result.put("message", b.name() + " 正在升级");
                return result;
            }
        }

        // 5. 新建非基础建筑需要市政厅 >= 1（JS: curLv===0 && id not in basic list）
        if (curLv == 0 && !BASIC_BUILDINGS.contains(buildingType)) {
            int commandLv = buildingLevel(playerId, "command");
            if (commandLv < 1) {
                result.put("success", false);
                result.put("message", "需先升级市政厅");
                return result;
            }
        }

        // 6. 检查等级上限（JS: buildMaxLevel）
        int max;
        if ("command".equals(buildingType)) {
            max = 10;
        } else {
            max = Math.min(10, Math.max(1, maxBuildingLevel(playerId)));
        }
        if (curLv >= max) {
            result.put("success", false);
            result.put("message", "已达当前市政厅上限");
            return result;
        }

        // 7. 所有新建建筑（含单栋建筑）均占用所属分组额度。
        if (curLv == 0) {
            List<Building> existingBuildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), buildingType);
            long builtCount = existingBuildings.stream()
                    .filter(bd -> bd.getLevel() != null && bd.getLevel() > 0)
                    .count();
            if (builtCount >= b.slots()) {
                result.put("success", false);
                result.put("message", b.name() + " 已达数量上限(" + b.slots() + "栋)");
                return result;
            }
            String groupKey = buildingGroup(buildingType);
            if (groupKey != null) {
                int remaining = groupSlotsCap(playerId, groupKey) - groupSlotsUsed(playerId, groupKey);
                if (remaining <= 0) {
                    result.put("success", false);
                    result.put("message", "res".equals(groupKey)
                            ? "资源建筑已达当前上限(" + groupSlotsCap(playerId, groupKey) + "栋，含新建中)，可升级市政厅扩容（最高32栋），或拆除建筑、取消新建"
                            : "军事建筑已达当前上限(" + groupSlotsCap(playerId, groupKey) + "栋，含新建中)，请升级市政厅、拆除建筑或取消新建");
                    return result;
                }
            }
        }

        // 8. 计算升级费用（含科技加速系数 buildMul）
        // JS: var mul = Math.pow(b.growth, lv) * Core.buildMul();
        //     cost[k] = Math.floor(b.baseCost[k] * mul);
        double costMul = Math.pow(b.growth(), curLv) * buildMul(playerId);
        Map<String, Integer> cost = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : b.baseCost().entrySet()) {
            cost.put(entry.getKey(), (int) Math.floor(entry.getValue() * costMul));
        }

        // 9. 检查资源是否充足
        if (!costEnough(playerId, cost)) {
            result.put("success", false);
            result.put("message", "资源不足");
            return result;
        }

        // 10. 计算建造时间（含科技加速系数）
        // JS: var lv = fromLevel + 1; var techMul = Math.max(0.5, Core.buildMul());
        //     sec = 30 * pow(2.4, lv - 1); return min(86400, ceil(sec * techMul));
        //   Lv.1=30s  Lv.2=1.2分  Lv.3=2.9分  Lv.4=6.9分  Lv.5=16.6分
        //   Lv.6=40分  Lv.7=1.6时  Lv.8=3.8时   Lv.9=9.2时  Lv.10=22时(封顶24h)
        double techMul = Math.max(0.5, buildMul(playerId));
        double sec = 30 * Math.pow(2.4, curLv);
        int duration = (int) Math.min(86400L, Math.ceil(sec * techMul));

        // 11. 扣除资源
        deductCosts(playerId, cost);

        // 12. 计算预计可获得声望（竣工时结算发放，开始升级时不提前发放）
        int totalCost = 0;
        for (int v : cost.values()) totalCost += v;
        int prestigeGain = totalCost > 0 ? Math.max(1, totalCost / 100) : 0;

        // 13. 创建施工记录
        long now = System.currentTimeMillis();
        Construction construction = new Construction();
        construction.setPlayerId(playerId);
        construction.setCitySlot(cityScope.slot(playerId));
        construction.setBuildingType(buildingType);
        construction.setTargetLevel(curLv + 1);
        construction.setStartAt(now);
        construction.setFinishAt(now + duration * 1000L);
        construction.setSlot(slotKey);
        constructionRepository.save(construction);

        // 14. 返回结果
        String label = b.name() + (multi ? " #" + (slot + 1) : "");
        result.put("success", true);
        result.put("message", label + " 开始升级，预计 " + duration + " 秒完成 (竣工获得 +" + prestigeGain + " 声望)");
        result.put("buildingType", buildingType);
        result.put("slot", multi ? slot : null);
        result.put("targetLevel", curLv + 1);
        result.put("cost", cost);
        result.put("buildTime", duration);
        result.put("startAt", now);
        result.put("finishAt", now + duration * 1000L);
        result.put("prestigeGain", prestigeGain);

        return result;
    }

    // ================================================================
    //  dismantle - 建筑拆除
    // ================================================================

    @Transactional
    public Map<String, Object> dismantle(Long playerId, String buildingType, int slot) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 校验建筑类型
        BuildingDef b = GameData.BUILDINGS.get(buildingType);
        if (b == null) {
            result.put("success", false);
            result.put("message", "无效的建筑类型: " + buildingType);
            return result;
        }

        // 2. 市政厅为核心枢纽，不可拆除
        if ("command".equals(buildingType)) {
            result.put("success", false);
            result.put("message", "市政厅为核心枢纽，不可拆除");
            return result;
        }

        // 3. 检查施工队数量上限
        List<Construction> allJobs = constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        if (allJobs.size() >= MAX_CONCURRENT) {
            result.put("success", false);
            result.put("message", "两支施工队都在忙，请等待完成");
            return result;
        }

        boolean multi = isMultiSlot(buildingType);
        Integer slotKey = multi ? slot : null;

        // 4. 获取当前等级
        int curLv;
        if (multi) {
            curLv = buildingLevel(playerId, buildingType, slot);
        } else {
            curLv = buildingLevel(playerId, buildingType);
        }

        if (curLv <= 0) {
            result.put("success", false);
            result.put("message", "建筑未建造，无法拆除");
            return result;
        }

        // 5. 检查该建筑/槽位是否正在施工
        for (Construction c : allJobs) {
            if (!buildingType.equals(c.getBuildingType())) continue;
            if (slotKey == null) {
                if (c.getSlot() == null) {
                    result.put("success", false);
                    result.put("message", b.name() + " 正在施工中");
                    return result;
                }
            } else if (slotKey.equals(c.getSlot())) {
                result.put("success", false);
                result.put("message", b.name() + " 正在施工中");
                return result;
            }
        }

        // 6. 计算拆除时间（与升级到当前等级消耗相同的时间）
        double techMul = Math.max(0.5, buildMul(playerId));
        double sec = 30 * Math.pow(2.4, curLv - 1);
        int duration = (int) Math.min(86400L, Math.ceil(sec * techMul));

        // 7. 目标等级：curLv - 1 (如 2级拆除后为1级，1级拆除后为0级即彻底消失)
        int targetLevel = curLv - 1;

        // 8. 创建施工记录
        long now = System.currentTimeMillis();
        Construction construction = new Construction();
        construction.setPlayerId(playerId);
        construction.setCitySlot(cityScope.slot(playerId));
        construction.setBuildingType(buildingType);
        construction.setTargetLevel(targetLevel);
        construction.setStartAt(now);
        construction.setFinishAt(now + duration * 1000L);
        construction.setSlot(slotKey);
        constructionRepository.save(construction);

        // 9. 返回结果
        String label = b.name() + (multi ? " #" + (slot + 1) : "");
        String actionMsg = targetLevel == 0 ? "开始拆除 (拆除后消失释放卡槽)" : ("开始拆除，预计降至 Lv." + targetLevel);
        result.put("success", true);
        result.put("message", label + " " + actionMsg + "，预计 " + duration + " 秒完成");
        result.put("buildingType", buildingType);
        result.put("slot", multi ? slot : null);
        result.put("targetLevel", targetLevel);
        result.put("buildTime", duration);
        result.put("startAt", now);
        result.put("finishAt", now + duration * 1000L);

        return result;
    }

    // ================================================================
    //  cancel - 取消施工 (支持升级与拆除)
    // ================================================================

    @Transactional
    public Map<String, Object> cancel(Long playerId, String buildingType, int slot) {
        Map<String, Object> result = new LinkedHashMap<>();

        BuildingDef b = GameData.BUILDINGS.get(buildingType);
        if (b == null) {
            result.put("success", false);
            result.put("message", "无效的建筑类型: " + buildingType);
            return result;
        }

        boolean multi = isMultiSlot(buildingType);
        Integer slotKey = multi ? slot : null;

        // 查找该建筑/槽位的施工记录
        List<Construction> allJobs = constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Construction target = null;
        for (Construction c : allJobs) {
            if (!buildingType.equals(c.getBuildingType())) continue;
            if (slotKey == null) {
                if (c.getSlot() == null) { target = c; break; }
            } else if (slotKey.equals(c.getSlot())) {
                target = c; break;
            }
        }

        if (target == null) {
            result.put("success", false);
            result.put("message", "未找到该建筑的施工记录");
            return result;
        }

        int curLv = multi ? buildingLevel(playerId, buildingType, slot) : buildingLevel(playerId, buildingType);
        boolean isDismantle = target.getTargetLevel() < curLv;

        if (isDismantle) {
            // 取消拆除：直接删除施工记录，不产生退款，建筑保留原级
            constructionRepository.delete(target);
            result.put("success", true);
            result.put("message", "已取消拆除");
            result.put("buildingType", buildingType);
            result.put("slot", multi ? slot : null);
            return result;
        }

        // 重新计算原始费用（含科技加速系数）
        int currentLevel = target.getTargetLevel() - 1;
        double costMul = Math.pow(b.growth(), currentLevel) * buildMul(playerId);
        Map<String, Integer> cost = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : b.baseCost().entrySet()) {
            cost.put(entry.getKey(), (int) Math.floor(entry.getValue() * costMul));
        }

        // 按比例返还资源
        refundCosts(playerId, cost, CANCEL_REFUND_RATIO);

        // 删除施工记录
        constructionRepository.delete(target);

        // 计算实际返还量
        Map<String, Integer> refund = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : cost.entrySet()) {
            refund.put(entry.getKey(), (int) Math.floor(entry.getValue() * CANCEL_REFUND_RATIO));
        }

        result.put("success", true);
        result.put("message", "已取消升级，返还50%资源");
        result.put("buildingType", buildingType);
        result.put("slot", multi ? slot : null);
        result.put("refund", refund);

        return result;
    }

    // ================================================================
    //  useSpeedUp - 对应 JS Depot.useUtilItem(speedUpXxx)
    //  使用加速符，将第一项施工的 finish_at 减去指定秒数
    //  若剩余时间 <= 0 则立即完成升级
    // ================================================================

    @Transactional
    public Map<String, Object> useSpeedUp(Long playerId, String itemId) {
        return useSpeedUp(playerId, itemId, null, null, null, 1);
    }

    @Transactional
    public Map<String, Object> useSpeedUp(Long playerId, String itemId, Long queueId) {
        return useSpeedUp(playerId, itemId, queueId, null, null, 1);
    }

    @Transactional
    public Map<String, Object> useSpeedUp(Long playerId, String itemId, Long queueId, int count) {
        return useSpeedUp(playerId, itemId, queueId, null, null, count);
    }

    @Transactional
    public Map<String, Object> useSpeedUp(Long playerId, String itemId, Long queueId, String building, Integer slot, int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        if (count <= 0) count = 1;

        // 1. 校验道具并原子扣减 count 个
        ItemDef item = speedUpSupport.consume(playerId, itemId, count, now, result);
        if (item == null) return result;
        long reduceMs = item.speedUpSeconds() * 1000L * count;

        // 2. 找到目标施工
        List<Construction> jobs = constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Construction target = null;
        if (queueId != null) {
            for (Construction c : jobs) {
                if (queueId.equals(c.getId()) && c.getFinishAt() != null && c.getFinishAt() > now) {
                    target = c;
                    break;
                }
            }
        }
        if (target == null && building != null) {
            for (Construction c : jobs) {
                if (building.equals(c.getBuildingType())
                        && (slot == null || java.util.Objects.equals(c.getSlot(), slot))
                        && (c.getFinishAt() == null || c.getFinishAt() > now)) {
                    target = c;
                    break;
                }
            }
        }
        if (target == null) {
            for (Construction c : jobs) {
                if (c.getFinishAt() == null || c.getFinishAt() > now) {
                    target = c;
                    break;
                }
            }
        }
        if (target == null) {
            // 没有可加速的施工，回退道具
            speedUpSupport.refund(playerId, itemId, count, now);
            result.put("success", false);
            result.put("message", "当前无施工中建筑");
            return result;
        }

        // 3. 计算新 finish_at
        long oldFinishAt = target.getFinishAt() != null ? target.getFinishAt() : now;
        long newFinishAt = oldFinishAt - reduceMs;
        long remainingMs = newFinishAt - now;

        boolean completed = remainingMs <= 0;
        if (completed) {
            // 剩余时间 ≤ 0，立即完成
            target.setFinishAt(now);
            constructionRepository.save(target);
            completeUpgrade(playerId, now);
        } else {
            // 仍有剩余时间，缩短 finish_at
            target.setFinishAt(newFinishAt);
            constructionRepository.save(target);
        }

        // 4. 同步删除 0 数量道具记录
        speedUpSupport.cleanupZeroCount(playerId, itemId);

        result.put("success", true);
        result.put("completed", completed);
        result.put("itemId", itemId);
        result.put("itemName", item.name());
        result.put("count", count);
        result.put("buildingType", target.getBuildingType());
        if (completed) {
            result.put("message", "⚡ " + item.name() + " ×" + count + " 使用成功，工程完成!");
        } else {
            long remainSec = Math.max(0, remainingMs / 1000);
            result.put("message", "⚡ " + item.name() + " ×" + count + " 使用成功，剩余 " + remainSec + " 秒");
            result.put("remainingSeconds", remainSec);
        }
        return result;
    }

    // ================================================================
    //  completeUpgrade - 施工完成 (支持升级与拆除)
    // ================================================================

    @Transactional
    public List<String> completeUpgrade(Long playerId, long now) {
        List<Construction> completed = constructionRepository.findByPlayerIdAndCitySlotAndFinishAtLessThanEqual(playerId, cityScope.slot(playerId), now);
        List<String> completedTypes = new ArrayList<>();

        for (Construction construction : completed) {
            String buildingType = construction.getBuildingType();
            int targetLevel = construction.getTargetLevel();
            Integer slot = construction.getSlot();

            BuildingDef b = GameData.BUILDINGS.get(buildingType);
            if (b == null) {
                constructionRepository.delete(construction);
                continue;
            }

            boolean multi = isMultiSlot(buildingType);

            if (targetLevel == 0) {
                // ===== 1级拆除完毕 -> 彻底删除建筑实体，释放卡槽 =====
                if (multi && slot != null) {
                    List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndTypeOrderByIdAsc(playerId, cityScope.slot(playerId), buildingType);
                    if (slot < buildings.size()) {
                        Building building = buildings.get(slot);
                        buildingRepository.delete(building);
                        buildings.remove(slot.intValue());
                        // 重排剩余同类型建筑的 slot 序号 (保持 0..N-1 连续)
                        for (int i = 0; i < buildings.size(); i++) {
                            Building rem = buildings.get(i);
                            if (rem.getSlot() == null || rem.getSlot() != i) {
                                rem.setSlot(i);
                                buildingRepository.save(rem);
                            }
                        }
                    }
                } else {
                    List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), buildingType);
                    if (!buildings.isEmpty()) {
                        buildingRepository.delete(buildings.get(0));
                    }
                }
                // 返还 30% Lv.1 基础消耗资源
                Map<String, Integer> cost = calcBuildCost(buildingType, 0);
                refundCosts(playerId, cost, DISMANTLE_REFUND_RATIO);
            } else if (multi && slot != null) {
                // ===== 多槽位建筑 (targetLevel > 0) =====
                List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndTypeOrderByIdAsc(playerId, cityScope.slot(playerId), buildingType);
                if (slot < buildings.size()) {
                    Building building = buildings.get(slot);
                    int currentLevel = building.getLevel() != null ? building.getLevel() : 0;
                    if (targetLevel < currentLevel) {
                        // 拆除降级 (如 2级 -> 1级)
                        building.setLevel(targetLevel);
                        buildingRepository.save(building);
                        // 返还 30% 该等级资源
                        Map<String, Integer> cost = calcBuildCost(buildingType, targetLevel);
                        refundCosts(playerId, cost, DISMANTLE_REFUND_RATIO);
                    } else {
                        // 升级
                        building.setLevel(Math.max(currentLevel, targetLevel));
                        buildingRepository.save(building);
                        grantPrestigeAndQuest(playerId, buildingType, targetLevel);
                    }
                } else {
                    // 新槽位新建
                    Building building = new Building();
                    building.setPlayerId(playerId);
                    building.setCitySlot(cityScope.slot(playerId));
                    building.setType(buildingType);
                    building.setLevel(targetLevel);
                    building.setSlot(slot);
                    buildingRepository.save(building);
                    grantPrestigeAndQuest(playerId, buildingType, targetLevel);
                }
            } else {
                // ===== 单槽位建筑 (targetLevel > 0) =====
                List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), buildingType);
                if (!buildings.isEmpty()) {
                    Building building = buildings.get(0);
                    int currentLevel = building.getLevel() != null ? building.getLevel() : 0;
                    if (targetLevel < currentLevel) {
                        // 拆除降级
                        building.setLevel(targetLevel);
                        buildingRepository.save(building);
                        Map<String, Integer> cost = calcBuildCost(buildingType, targetLevel);
                        refundCosts(playerId, cost, DISMANTLE_REFUND_RATIO);
                    } else {
                        // 升级
                        building.setLevel(Math.max(currentLevel, targetLevel));
                        buildingRepository.save(building);
                        grantPrestigeAndQuest(playerId, buildingType, targetLevel);
                    }
                } else {
                    Building building = new Building();
                    building.setPlayerId(playerId);
                    building.setCitySlot(cityScope.slot(playerId));
                    building.setType(buildingType);
                    building.setLevel(targetLevel);
                    building.setSlot(0);
                    buildingRepository.save(building);
                    grantPrestigeAndQuest(playerId, buildingType, targetLevel);
                }
            }

            constructionRepository.delete(construction);
            completedTypes.add(buildingType);

            // 主线与每日任务进度钩子
            try {
                questService.onEvent(playerId, "BUILD_LEVEL_SUM");
                questService.onEvent(playerId, "BUILD_COUNT");
            } catch (Exception ignored) { /* 任务系统不可用不能阻塞建造 */ }
        }
        return completedTypes;
    }

    private void grantPrestigeAndQuest(Long playerId, String buildingType, int targetLevel) {
        int fromLevel = Math.max(0, targetLevel - 1);
        int prestigeGain = calculatePrestigeGain(playerId, buildingType, fromLevel);
        if (prestigeGain > 0) {
            Player player = playerRepository.findById(playerId).orElse(null);
            if (player != null) {
                player.setPrestige((player.getPrestige() != null ? player.getPrestige() : 0) + prestigeGain);
                playerRepository.save(player);
            }
        }
        try {
            questService.onEvent(playerId, "BUILD_UPGRADE_DONE", buildingType, 1);
        } catch (Exception ignored) {}
    }

    public int calculatePrestigeGain(Long playerId, String buildingType, int fromLevel) {
        BuildingDef b = GameData.BUILDINGS.get(buildingType);
        if (b == null) return 0;
        double costMul = Math.pow(b.growth(), Math.max(0, fromLevel)) * buildMul(playerId);
        int totalCost = 0;
        for (Map.Entry<String, Integer> entry : b.baseCost().entrySet()) {
            totalCost += (int) Math.floor(entry.getValue() * costMul);
        }
        return totalCost > 0 ? Math.max(1, totalCost / 100) : 0;
    }

    // ================================================================
    //  Helper: buildingLevel (无 slot)
    //  对应 JS Core.buildingLevel - 返回所有槽位等级之和
    // ================================================================

    public int buildingLevel(Long playerId, String buildingType) {
        List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), buildingType);
        int sum = 0;
        for (Building b : buildings) {
            sum += b.getLevel() != null ? b.getLevel() : 0;
        }
        return sum;
    }

    // ================================================================
    //  Helper: buildingLevel (指定 slot)
    //  对应 JS: arr[slotIdx] || 0
    // ================================================================

    public int buildingLevel(Long playerId, String buildingType, int slot) {
        if (isMultiSlot(buildingType)) {
            List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndTypeOrderByIdAsc(playerId, cityScope.slot(playerId), buildingType);
            if (slot >= 0 && slot < buildings.size()) {
                Building b = buildings.get(slot);
                return b.getLevel() != null ? b.getLevel() : 0;
            }
            return 0;
        } else {
            return buildingLevel(playerId, buildingType);
        }
    }

    // ================================================================
    //  Helper: calcBuildCost
    //  对应 JS G.buildCost（不含 buildMul 科技系数，由 upgrade 内部叠加）
    //  JS: mul = Math.pow(b.growth, lv); cost[k] = Math.floor(b.baseCost[k] * mul)
    // ================================================================

    public Map<String, Integer> calcBuildCost(String buildingType, int currentLevel) {
        BuildingDef b = GameData.BUILDINGS.get(buildingType);
        if (b == null) return Collections.emptyMap();

        Map<String, Integer> cost = new LinkedHashMap<>();
        double mul = Math.pow(b.growth(), currentLevel);
        for (Map.Entry<String, Integer> entry : b.baseCost().entrySet()) {
            cost.put(entry.getKey(), (int) Math.floor(entry.getValue() * mul));
        }
        return cost;
    }

    // ================================================================
    //  Helper: calcBuildTime
    //  对应 JS G.buildDuration（不含 techMul 科技系数，由 upgrade 内部叠加）
    //  JS: lv = fromLevel + 1; return Math.ceil(20 * Math.pow(1.45, lv - 1))
    //  简化: ceil(20 * 1.45^currentLevel)
    // ================================================================

    public int calcBuildTime(String buildingType, int currentLevel) {
        return (int) Math.ceil(20 * Math.pow(1.45, currentLevel));
    }

    // ================================================================
    //  Helper: maxBuildingLevel
    //  对应 JS buildMaxLevel - 返回市政厅等级（其他建筑上限为 min(10, commandLevel)）
    // ================================================================

    public int maxBuildingLevel(Long playerId) {
        return buildingLevel(playerId, "command");
    }

    // ================================================================
    //  Helper: costEnough
    //  对应 JS Core.costEnough
    // ================================================================

    public boolean costEnough(Long playerId, Map<String, Integer> costs) {
        Resources r = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (r == null) return false;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            int have = getResource(r, entry.getKey());
            if (have < entry.getValue()) return false;
        }
        return true;
    }

    // ================================================================
    //  Helper: deductCosts
    //  对应 JS Core.payCost
    // ================================================================

    public void deductCosts(Long playerId, Map<String, Integer> costs) {
        Resources r = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (r == null) return;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            int current = getResource(r, entry.getKey());
            setResource(r, entry.getKey(), current - entry.getValue());
        }
        resourcesRepository.save(r);
    }

    // ================================================================
    //  Helper: refundCosts
    //  按比例返还资源
    // ================================================================

    public void refundCosts(Long playerId, Map<String, Integer> costs, double ratio) {
        Resources r = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (r == null) return;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            int current = getResource(r, entry.getKey());
            int refund = (int) Math.floor(entry.getValue() * ratio);
            setResource(r, entry.getKey(), current + refund);
        }
        resourcesRepository.save(r);
    }

    // ================================================================
    //  Private helpers
    // ================================================================

    /** 科技加速系数 - 对应 JS Core.buildMul: 1 - 0.05 * log_build */
    private double buildMul(Long playerId) {
        int logBuild = techLevel(playerId, "log_build");
        return 1 - 0.05 * logBuild;
    }

    /** 获取玩家某项科技等级 */
    private int techLevel(Long playerId, String techType) {
        List<Technology> techs = technologyRepository.findByPlayerIdAndType(playerId, techType);
        if (techs.isEmpty()) return 0;
        return techs.get(0).getLevel() != null ? techs.get(0).getLevel() : 0;
    }

    /** 是否多槽位建筑 */
    private boolean isMultiSlot(String buildingType) {
        return MULTI_SLOT.contains(buildingType);
    }

    /** 获取建筑所属分组 - 对应 JS Core.buildingGroup */
    private String buildingGroup(String buildingType) {
        for (Map.Entry<String, List<String>> entry : GROUPS.entrySet()) {
            if (entry.getValue().contains(buildingType)) return entry.getKey();
        }
        return null;
    }

    /** 分组已用槽位数 - 对应 JS Core.groupSlotsUsed */
    private int groupSlotsUsed(Long playerId, String groupKey) {
        List<String> buildingTypes = GROUPS.get(groupKey);
        if (buildingTypes == null) return 0;
        int used = 0;
        for (String type : buildingTypes) {
            List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), type);
            for (Building b : buildings) {
                if (b.getLevel() != null && b.getLevel() > 0) used++;
            }
        }
        if ("res".equals(groupKey) || "army".equals(groupKey)) {
            used += (int) constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).stream()
                    .filter(c -> buildingTypes.contains(c.getBuildingType()) && Integer.valueOf(1).equals(c.getTargetLevel()))
                    .filter(c -> {
                        int cur = c.getSlot() != null
                                ? buildingLevel(playerId, c.getBuildingType(), c.getSlot())
                                : buildingLevel(playerId, c.getBuildingType());
                        return cur == 0;
                    })
                    .count();
        }
        return used;
    }

    /** 资源区基础12栋，每级市政厅增加2栋，最高32栋；军事区采用相同扩容规则。 */
    private int groupSlotsCap(Long playerId, String groupKey) {
        int commandLv = buildingLevel(playerId, "command");
        if ("res".equals(groupKey)) {
            return Math.min(GameConstants.GROUP_SLOTS_RES_MAX,
                    GameConstants.GROUP_SLOTS_RES + Math.max(0, commandLv) * 2);
        }
        int base = GameConstants.GROUP_SLOTS_ARMY;
        return Math.min(GameConstants.GROUP_SLOTS_ARMY_MAX, base + Math.max(0, commandLv) * 2);
    }

    /** 从 Resources 实体读取指定资源值 */
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

    /** 向 Resources 实体写入指定资源值 */
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
