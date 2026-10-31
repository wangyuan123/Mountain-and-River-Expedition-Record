package com.wargame.service;

import com.wargame.util.DurationText;

import com.wargame.util.GameClock;
import com.wargame.model.constants.GameConstants;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
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
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TickService resourceEconomy;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private GarrisonFoodTimeline timeline;
    @org.springframework.beans.factory.annotation.Autowired private FortService fortService;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private GarrisonFoodService garrisonFood;


    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;
    @org.springframework.beans.factory.annotation.Autowired
    private WorldTerrainService terrain;
    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.repository.OfficerRepository officerRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private PrerequisiteService prerequisiteService;

    @org.springframework.beans.factory.annotation.Autowired private PrisonerService prisonerService;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private VipService vipService;

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
            "army", List.of("command", "house", "factory", "lightfactory", "heavyfactory", "airport", "port", "shipyard",
                            "academy", "staff", "headquarters", "lab", "radar", "guild_radar", "wall", "apron", "liaison", "depot", "transit", "hospital", "exchange", "prisoner_camp")
    );

    /** 每城开局提供 3 支施工队，新建、升级与拆除共用；与前端 build.js 保持一致。 */
    private static final int MAX_CONCURRENT = 3;

    private record BuildDurationCurve(double baseSeconds, double growth, long capSeconds) {}

    private static final BuildDurationCurve DEFAULT_BUILD_DURATION =
            new BuildDurationCurve(30, 2.4, 24L * 60 * 60);
    // 核心建筑后期工期单独拉长；需与前端 build.js 的曲线和上限保持一致。
    private static final Map<String, BuildDurationCurve> SPECIAL_BUILD_DURATIONS = Map.of(
            "command", new BuildDurationCurve(60, 2.5, 72L * 60 * 60),
            "lab", new BuildDurationCurve(45, 2.6, 72L * 60 * 60),
            "headquarters", new BuildDurationCurve(45, 2.5, 48L * 60 * 60)
    );

    /** 剩余工期不超过 5 分钟时可免费完工；通过游戏状态同步给前端。 */
    public static final int FREE_SPEED_UP_SECONDS = 300;

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
        resourceEconomy.beforeResourceChange(playerId, cityScope.slot(playerId));
        Map<String, Object> result = new LinkedHashMap<>();
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        // 1. 校验建筑类型
        BuildingDef b = GameData.BUILDINGS.get(buildingType);
        if (b == null) {
            result.put("success", false);
            result.put("message", "无效的建筑类型: " + buildingType);
            return result;
        }

        // 2. 检查当前城市的施工队数量上限
        List<Construction> allJobs = constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        int maxConcurrent = vipService != null ? vipService.getBuildQueueLimit(playerRepository.findById(playerId).orElse(null)) : MAX_CONCURRENT;
        if (allJobs.size() >= maxConcurrent) {
            result.put("success", false);
            result.put("message", maxConcurrent + " 支施工队都在忙，请等待完成");
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

        // 造船厂与军港共用沿海准入，保留旧城 legacyNaval 的既有海军资格。
        if (("port".equals(buildingType) || "shipyard".equals(buildingType)) && curLv == 0) {
            terrain.ensure();
            var city=cityScope.selected(playerId);
            if(city.isPresent()&&!terrain.canUsePort(city.get())){
                result.put("success", false);
                result.put("message", "新建" + b.name() + "需要沿海城市，请在海边建立分城");
                return result;
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
            max = GameConstants.BUILDING_MAX_LEVEL;
        } else {
            max = Math.min(GameConstants.BUILDING_MAX_LEVEL, Math.max(1, maxBuildingLevel(playerId)));
        }
        if (curLv >= max) {
            result.put("success", false);
            result.put("message", "已达当前市政厅上限");
            return result;
        }

        // Evaluate the target level before charging resources; construction in progress never satisfies a prerequisite.
        List<Map<String, Object>> missing = prerequisiteService.unmet(
                playerId, cityScope.slot(playerId), "buildings", buildingType, curLv + 1);
        if (!missing.isEmpty()) {
            result.put("success", false);
            result.put("message", prerequisiteService.message(missing));
            result.put("missingPrerequisites", missing);
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
                            ? "资源建筑已达上限(" + groupSlotsCap(playerId, groupKey) + "栋，含新建中），请拆除建筑或取消新建"
                            : "军事建筑已达上限(" + groupSlotsCap(playerId, groupKey) + "栋，含新建中），请拆除建筑或取消新建");
                    return result;
                }
            }
        }

        // 8. 计算升级费用（含科技加速系数 buildMul）
        // JS: var mul = Math.pow(b.growth, lv) * Core.buildMul();
        //     cost[k] = Math.floor(b.baseCost[k] * mul);
        Map<String, Integer> cost = upgradeCost(buildingType, curLv, buildMul(playerId));

        // 9. 检查资源是否充足
        if (!costEnough(playerId, cost)) {
            result.put("success", false);
            result.put("message", "资源不足");
            return result;
        }

        // 10. 计算建造时间（按建筑曲线封顶，并应用科技和市长工期倍率）
        double techMul = Math.max(0.35, buildDurationMul(playerId));
        int duration = calcBuildTime(buildingType, curLv, techMul);

        // 11. 扣除资源
        deductCosts(playerId, cost);

        // 12. 计算预计可获得声望（竣工时结算发放，开始升级时不提前发放）
        int totalCost = 0;
        for (int v : cost.values()) totalCost += v;
        int prestigeGain = totalCost > 0 ? Math.max(1, totalCost / 100) : 0;

        // 13. 创建施工记录
        long now = GameClock.now();
        Construction construction = new Construction();
        construction.setPlayerId(playerId);
        construction.setCitySlot(cityScope.slot(playerId));
        construction.setBuildingType(buildingType);
        construction.setTargetLevel(curLv + 1);
        construction.setStartAt(now);
        construction.setFinishAt(now + duration * 1000L);
        construction.setSlot(slotKey);
        construction.setPrerequisiteVersion(prerequisiteService.version());
        construction.setPrerequisiteRequirements(prerequisiteService.snapshot("buildings", buildingType, curLv + 1));
        construction.setCostSnapshot(JsonUtil.toJson(cost));
        constructionRepository.save(construction);

        // 14. 返回结果
        String label = b.name() + (multi ? " #" + (slot + 1) : "");
        result.put("success", true);
        result.put("message", label + " 开始升级，预计 " + DurationText.seconds(duration) + "完成 (竣工获得 +" + prestigeGain + " 声望)");
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
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

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
        int maxConcurrent = vipService != null ? vipService.getBuildQueueLimit(playerRepository.findById(playerId).orElse(null)) : MAX_CONCURRENT;
        if (allJobs.size() >= maxConcurrent) {
            result.put("success", false);
            result.put("message", maxConcurrent + " 支施工队都在忙，请等待完成");
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

        // 6. 计算拆除时间（与升至当前等级使用同一建筑曲线）
        double techMul = Math.max(0.35, buildDurationMul(playerId));
        int duration = calcBuildTime(buildingType, curLv - 1, techMul);

        // 7. 目标等级：curLv - 1 (如 2级拆除后为1级，1级拆除后为0级即彻底消失)
        int targetLevel = curLv - 1;
        if ("wall".equals(buildingType) && fortService.totalForts(playerId) > (long) targetLevel * FortService.FORT_CAP_PER_WALL_LEVEL) {
            result.put("success", false);
            result.put("message", "降级后城防容量不足，请先拆除多余工事");
            return result;
        }
        // 降级保留足够容量，完全拆除必须先清空俘虏池；与入池共用账号锁。
        if ("prisoner_camp".equals(buildingType)) {
            long stock = cityScope.selected(playerId).map(c -> prisonerService.currentTotal(playerId, c.getId())).orElse(0L);
            if (stock > (long) targetLevel * 2000) {
                result.put("success", false);
                result.put("message", targetLevel == 0 ? "请先招募或释放营内战俘" : "降级后的容量不足，请先招募或释放战俘");
                return result;
            }
        }
        String affected = prerequisiteService.affectedTask(playerId, cityScope.slot(playerId),
                buildingType, slotKey, targetLevel);
        if (affected != null) {
            result.put("success", false);
            result.put("message", "无法拆除：" + affected + "仍需此建筑等级");
            return result;
        }

        // 8. 创建施工记录
        long now = GameClock.now();
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
        result.put("message", label + " " + actionMsg + "，预计 " + DurationText.seconds(duration) + "完成");
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
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

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

        // 新订单只按开工实扣快照退款；未经过迁移的旧测试/导入队列保留旧规则兼容。
        int currentLevel = target.getTargetLevel() - 1;
        double costMul = Math.pow(b.growth(), currentLevel) * buildMul(playerId);
        Map<String, Integer> cost = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : b.baseCost().entrySet()) {
            cost.put(entry.getKey(), (int) Math.floor(entry.getValue() * costMul));
        }
        if (target.getCostSnapshot() != null) cost = JsonUtil.parseIntMap(target.getCostSnapshot());

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

    /** 免费完成当前城市的指定工程，沿用正常完工的建筑、声望和任务结算。 */
    @Transactional
    public Map<String, Object> freeSpeedUp(Long playerId, Long queueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        // 与 TickService 共用玩家锁，串行处理自动完工及重复点击，避免重复结算。
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        Construction target = constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId))
                .stream().filter(c -> c.getId().equals(queueId)).findFirst().orElse(null);
        if (target == null || target.getFinishAt() == null) {
            result.put("message", "该工程已完成或不存在，请刷新施工队列");
            return result;
        }
        long now = GameClock.now();
        if (target.getFinishAt() - now > FREE_SPEED_UP_SECONDS * 1000L) {
            result.put("message", "剩余施工时间不超过5分钟时才可免费加速");
            return result;
        }
        target.setFinishAt(now);
        constructionRepository.save(target);
        completeUpgrade(playerId, now);
        result.put("success", true);
        result.put("completed", true);
        result.put("queueId", queueId);
        result.put("message", "免费加速成功，工程已完成！");
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
        long now = GameClock.now();
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
            result.put("message", "⚡ " + item.name() + " ×" + count + " 使用成功，剩余 " + DurationText.millis(remainingMs));
            result.put("remainingSeconds", remainSec);
        }
        return result;
    }

    // ================================================================
    //  completeUpgrade - 施工完成 (支持升级与拆除)
    // ================================================================

    /** 结算到期施工；军备日常只统计实际升高等级的新建或升级，拆除与降级不计入。 */
    @Transactional
    public List<String> completeUpgrade(Long playerId, long now) {
        if (timeline != null && timeline.eventTime() == null)
            return timeline.advance(playerId, now, null).buildsFor(cityScope.slot(playerId));
        List<Construction> completed = constructionRepository.findByPlayerIdAndCitySlotAndFinishAtLessThanEqual(playerId, cityScope.slot(playerId), now);
        List<String> completedTypes = new ArrayList<>();

        for (Construction construction : completed) {
            if (garrisonFood != null) garrisonFood.beforeScheduledChange(playerId, construction.getFinishAt());
            String buildingType = construction.getBuildingType();
            int targetLevel = construction.getTargetLevel();
            Integer slot = construction.getSlot();

            // 兼容升级前已经排定的城墙降级：不删除玩家工事，超容量时撤销该降级工程。
            if ("wall".equals(buildingType) && targetLevel < buildingLevel(playerId, "wall")
                    && fortService.totalForts(playerId) > (long) targetLevel * FortService.FORT_CAP_PER_WALL_LEVEL) {
                constructionRepository.delete(construction);
                completedTypes.add("城防降级已取消：请先拆除多余工事");
                continue;
            }

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
                        // Hibernate 默认先 UPDATE 后 DELETE；先落库删除，才能将下一栋移至有唯一约束的零号位。
                        buildingRepository.flush();
                        buildings.remove(slot.intValue());
                        // 重排剩余同类型建筑的 slot 序号 (保持 0..N-1 连续)
                        for (int i = 0; i < buildings.size(); i++) {
                            Building rem = buildings.get(i);
                            if (rem.getSlot() == null || rem.getSlot() != i) {
                                rem.setSlot(i);
                                buildingRepository.save(rem);
                            }
                        }
                        // 队列使用同类型建筑的列表位次；重排后同步后续升级、拆除及新建，避免完工落到别的建筑。
                        for (Construction pending : constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId))) {
                            if (buildingType.equals(pending.getBuildingType())
                                    && pending.getSlot() != null && pending.getSlot() > slot) {
                                pending.setSlot(pending.getSlot() - 1);
                                constructionRepository.save(pending);
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
                        grantPrestigeAndQuest(playerId, construction);
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
                    grantPrestigeAndQuest(playerId, construction);
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
                        grantPrestigeAndQuest(playerId, construction);
                    }
                } else {
                    Building building = new Building();
                    building.setPlayerId(playerId);
                    building.setCitySlot(cityScope.slot(playerId));
                    building.setType(buildingType);
                    building.setLevel(targetLevel);
                    building.setSlot(0);
                    buildingRepository.save(building);
                    grantPrestigeAndQuest(playerId, construction);
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

    /** 完工奖励兑现开工实扣快照，施工期间的降费科技不能重估已付款订单。 */
    private void grantPrestigeAndQuest(Long playerId, Construction construction) {
        String buildingType = construction.getBuildingType();
        long paid = construction.getCostSnapshot() == null ? -1 : JsonUtil.parseIntMap(construction.getCostSnapshot()).values().stream().mapToLong(Integer::longValue).sum();
        int prestigeGain = paid < 0 ? calculatePrestigeGain(playerId, buildingType, Math.max(0, construction.getTargetLevel() - 1))
                : paid == 0 ? 0 : (int) Math.max(1, paid / 100);
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

    /** 材料报价与开工扣费共用此公式，取整发生在折扣之后。 */
    private Map<String, Integer> upgradeCost(String type, int level, double discount) {
        BuildingDef definition = GameData.BUILDINGS.get(type);
        Map<String, Integer> result = new LinkedHashMap<>();
        definition.baseCost().forEach((key, value) -> result.put(key,
                (int) Math.floor(value * Math.pow(definition.growth(), level) * discount)));
        return result;
    }

    /** 返回本城各等级的后端报价；执行仍在账号锁内重新计算，不能提交报价作为扣费依据。 */
    public Map<String, Object> previewLevels(Long playerId) {
        double discount = buildMul(playerId), duration = Math.max(.35, buildDurationMul(playerId));
        Map<String, Object> result = new LinkedHashMap<>();
        GameData.BUILDINGS.forEach((id, definition) -> {
            List<Map<String, Object>> levels = new ArrayList<>();
            for (int level = 0; level <= GameConstants.BUILDING_MAX_LEVEL; level++) {
                Map<String, Integer> refund = new LinkedHashMap<>();
                calcBuildCost(id, Math.max(0, level - 1)).forEach((key, value) ->
                        refund.put(key, (int) Math.floor(value * DISMANTLE_REFUND_RATIO)));
                levels.add(Map.of("cost", upgradeCost(id, level, discount),
                        "duration", calcBuildTime(id, level, duration), "refund", refund));
            }
            result.put(id, Map.of("maxLevel", "command".equals(id) ? GameConstants.BUILDING_MAX_LEVEL
                    : Math.min(GameConstants.BUILDING_MAX_LEVEL, Math.max(1, maxBuildingLevel(playerId))), "levels", levels));
        });
        return result;
    }

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

    /**
     * 按建筑专属曲线计算升至下一级的基础工期，不含科技和市长加速。
     * @param buildingType 建筑类型
     * @param currentLevel 开工前等级
     * @return 基础工期（秒）
     */
    public int calcBuildTime(String buildingType, int currentLevel) {
        return calcBuildTime(buildingType, currentLevel, 1.0);
    }

    private int calcBuildTime(String buildingType, int currentLevel, double durationMultiplier) {
        BuildDurationCurve curve = SPECIAL_BUILD_DURATIONS.getOrDefault(buildingType, DEFAULT_BUILD_DURATION);
        double seconds = curve.baseSeconds() * Math.pow(curve.growth(), Math.max(0, currentLevel)) * durationMultiplier;
        return (int) Math.min(curve.capSeconds(), Math.ceil(seconds));
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
            long have = getResource(r, entry.getKey());
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
            long current = getResource(r, entry.getKey());
            setResource(r, entry.getKey(), current - entry.getValue());
        }
        resourcesRepository.save(r);
    }

    // ================================================================
    //  Helper: refundCosts
    //  按比例返还资源
    // ================================================================

    public void refundCosts(Long playerId, Map<String, Integer> costs, double ratio) {
        resourceEconomy.beforeResourceChange(playerId, cityScope.slot(playerId));
        Resources r = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (r == null) return;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            int refund = (int) Math.floor(entry.getValue() * ratio);
            switch (entry.getKey()) {
                case "food", "steel", "oil", "rare", "gold" -> r.addResource(entry.getKey(), refund);
                default -> { }
            }
        }
        resourcesRepository.save(r);
    }

    // ================================================================
    //  Private helpers
    // ================================================================

    /** 科技加速系数 - 对应 JS Core.buildMul: 1 - 0.05 * log_build */
    public double buildMul(Long playerId) {
        int logBuild = techLevel(playerId, "log_build");
        return 1 - 0.05 * logBuild;
    }

    /** 建造工期系数：叠加科技系数与市长营造技能加速（每级缩短4%工期，最高20%）及 VIP 建造特许加速 */
    private double buildDurationMul(Long playerId) {
        double mul = buildMul(playerId);
        int constructLv = getMayorConstructLevel(playerId);
        if (constructLv > 0) {
            mul *= (1.0 - 0.04 * constructLv);
        }
        if (vipService != null && playerRepository != null) {
            Player p = playerRepository.findById(playerId).orElse(null);
            double vipBonus = vipService.getBuildSpeedBonus(p);
            if (vipBonus > 0) {
                mul *= (1.0 - vipBonus);
            }
        }
        return mul;
    }

    private int getMayorConstructLevel(Long playerId) {
        if (officerRepository == null || cityScope == null || playerId == null) return 0;
        try {
            List<Officer> list = officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, cityScope.slot(playerId), "mayor");
            if (list == null || list.isEmpty()) return 0;
            Officer mayor = list.get(0);
            if (mayor.getSkills() == null || mayor.getSkills().isBlank()) return 0;
            List<Map<String, Object>> skills = JsonUtil.parseList(mayor.getSkills());
            for (Map<String, Object> sk : skills) {
                if ("construct".equals(sk.get("id"))) {
                    Object lv = sk.get("lv");
                    return lv instanceof Number ? ((Number) lv).intValue() : 0;
                }
            }
        } catch (Exception ignored) {}
        return 0;
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

    /** 资源区与军工区各固定35个共享卡槽，容量不受市政厅等级影响。 */
    private int groupSlotsCap(Long playerId, String groupKey) {
        return "res".equals(groupKey) ? GameConstants.GROUP_SLOTS_RES : GameConstants.GROUP_SLOTS_ARMY;
    }

    /** 区域卡槽报价与新建准入共用已建成、在建占位统计。 */
    public Map<String, Object> groupSlotsView(Long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : GROUPS.keySet()) {
            int cap = groupSlotsCap(playerId, key), used = groupSlotsUsed(playerId, key);
            result.put(key, Map.of("cap", cap, "used", used, "remaining", Math.max(0, cap - used)));
        }
        return result;
    }

    /** 从 Resources 实体读取指定资源值 */
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

    /** 向 Resources 实体写入指定资源值 */
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
