package com.wargame.service;

import com.wargame.util.GameClock;
import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TickService {
    @org.springframework.beans.factory.annotation.Autowired @Lazy private GameplayViewService gameplayViews;
    @org.springframework.beans.factory.annotation.Autowired private SpeedUpSupport speedUpSupport;

    @org.springframework.beans.factory.annotation.Autowired private UnitUnlockService unitUnlocks;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    private static final Logger log = LoggerFactory.getLogger(TickService.class);

    /** 欠薪不制造黄金负库存，处罚状态单独持久化。 */
    private static final int GOLD_FLOOR = 0;

    private final PlayerRepository playerRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final TechnologyRepository technologyRepository;
    private final OfficerRepository officerRepository;
    private final EquipmentService equipmentService;
    private final CityStateRepository cityStateRepository;

    private final WorldViewService worldViewService;
    private final MarchService marchService;
    private final ArmyService armyService;
    private final BuildService buildService;
    private final MarchRepository marchRepository;
    private final ConstructionRepository constructionRepository;
    private final WebSocketPushService pushService;
    private final TechService techService;



    @org.springframework.beans.factory.annotation.Value("${game.max-offline-hours:8}")
    private int maxOfflineHours = 8;

    private static final List<String> RES_KEYS = List.of("food", "steel", "oil", "rare");

    public TickService(PlayerRepository playerRepository,
                       ResourcesRepository resourcesRepository,
                       BuildingRepository buildingRepository,
                       ArmyUnitRepository armyUnitRepository,
                       TechnologyRepository technologyRepository,
                       OfficerRepository officerRepository,
                       EquipmentService equipmentService,
                       CityStateRepository cityStateRepository,
                       @Lazy MarchService marchService,
                       @Lazy ArmyService armyService,
                       @Lazy BuildService buildService,
                       @Lazy TechService techService,
                       MarchRepository marchRepository,
                       ConstructionRepository constructionRepository,
                       @Lazy WebSocketPushService pushService, WorldViewService worldViewService) {
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.buildingRepository = buildingRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.technologyRepository = technologyRepository;
        this.officerRepository = officerRepository;
        this.equipmentService = equipmentService;
        this.cityStateRepository = cityStateRepository;
        this.marchService = marchService;
        this.armyService = armyService;
        this.buildService = buildService;
        this.techService = techService;
        this.marchRepository = marchRepository;
        this.constructionRepository = constructionRepository;
        this.pushService = pushService;
        this.worldViewService = worldViewService;
    }

    // ================================================================
    // tick - Process a single player's tick
    // ================================================================

    @org.springframework.beans.factory.annotation.Autowired private PlayerCityRepository playerCities;

    @org.springframework.beans.factory.annotation.Autowired private GarrisonFoodService garrisonFood;
    @org.springframework.beans.factory.annotation.Autowired @Lazy private GarrisonFoodTimeline timeline;
    @org.springframework.beans.factory.annotation.Autowired private CityBattleLockService cityBattleLock;
    private final ThreadLocal<Set<String>> resourceChanges = ThreadLocal.withInitial(HashSet::new);

    /** 所有即时资源收支先结旧账；事件内部沿用事件时刻，同城回调不得递归结算。 */
    @Transactional
    public void beforeResourceChange(Long owner, int slot) {
        String key = owner + ":" + slot;
        Set<String> active = resourceChanges.get();
        if (!active.add(key)) return;
        try (var ignored = cityScope.enter(owner, slot)) {
            if (timeline.eventTime() != null) settleEconomyAt(owner, slot, timeline.eventTime());
            else settleEconomyBeforeChange(owner);
        } finally {
            active.remove(key);
            if (active.isEmpty()) resourceChanges.remove();
        }
    }

    @Transactional
    public void tick(Long playerId) {
        marchService.lockInterceptionParticipants(playerId);
        Player current = playerRepository.lockById(playerId).orElse(null);
        // 恢复期继续结算；到期后绝不能补建已经清除的个人资产。
        if (current == null || !current.isGameInitialized() || current.deletionDue(System.currentTimeMillis())) return;
        timeline.advance(playerId, GameClock.now(), null);
        // Each city settles its own clock and queues. Slot zero also advances the scheduler's account clock.
        try (var ignored = cityScope.enter(playerId, 0)) { tickCity(playerId); }
        for (PlayerCity city : playerCities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId)) {
            if (city.getCitySlot() == 0 || city.getReadyAt() > GameClock.now()) continue;
            try (var ignored = cityScope.enter(city)) { tickCity(playerId); }
        }
    }

    /** 变更税率或军官名单前只结清当前城市经济，不推进其他城市或重复执行行军结算。 */
    @Transactional
    public void settleEconomyBeforeChange(Long playerId) {
        marchService.lockInterceptionParticipants(playerId);
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        timeline.advance(playerId, GameClock.now(), null);
        tickCity(playerId, false);
    }

    private void tickCity(Long playerId) { tickCity(playerId, true); }

    /** 离线收益上限按整段窗口裁剪，不能因窗口内队列较多而重复获得多段上限。 */
    public void beginEconomyWindow(Long owner, long now) {
        long cutoff = now - Math.max(1, maxOfflineHours) * 3600000L;
        Set<Integer> slots = new LinkedHashSet<>(List.of(0));
        playerCities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(owner).forEach(c -> slots.add(c.getCitySlot()));
        for (int slot : slots) try (var ignored = cityScope.enter(owner, slot)) {
            if (cityScope.frozen(owner)) continue;
            CityEconomy local = cityScope.economy(owner);
            if (local.getLastTick() != null && local.getLastTick() < cutoff) {
                local.setLastTick(cutoff); cityScope.saveEconomy(owner);
            }
            // 两套粮账使用同一个补结窗口，已离开的旧驻军也不能留下更长账期。
            cityScope.selected(owner).ifPresent(c -> {
                if (c.getGarrisonFoodAt() != null && c.getGarrisonFoodAt() < cutoff) c.setGarrisonFoodAt(cutoff);
            });
        }
    }

    /** 时间轴回调只结清旧配置区间，不递归执行队列或行军。 */
    @Transactional
    public void settleEconomyAt(Long playerId, int slot, long at) {
        try (var selected = cityScope.enter(playerId, slot)) { tickCity(playerId, false, at); }
    }

    private void tickCity(Long playerId, boolean processQueues) {
        tickCity(playerId, processQueues, GameClock.now());
    }

    private void tickCity(Long playerId, boolean processQueues, long now) {
        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null) return;

        if (cityScope.frozen(playerId)) {
            // Freeze the city clock as well as its writes; no catch-up production after handoff.
            cityScope.economy(playerId).setLastTick(now);
            cityScope.saveEconomy(playerId);
            return;
        }
        long lastTick = cityScope.economy(playerId).getLastTick() != null ? cityScope.economy(playerId).getLastTick() : now;
        if (now < lastTick) return; // 旧事件不得把已经结算的城市时钟倒拨。
        // 新建或历史账号可能尚未写入 lastTick。即使本次没有经济时间差，
        // 也必须继续处理到达行军和战术回合，避免离线战斗永久停滞。
        double dt = Math.max(0, (now - lastTick) / 1000.0);
        dt = Math.min(dt, Math.max(1, maxOfflineHours) * 3600.0);
        double hours = dt / 3600.0;

        // Get mayor
        Officer mayor = getOfficerByRole(playerId, "mayor");
        double mayorKnow = mayor != null ? equipmentService.attributes(mayor).knowledge() : 0;

        // Get capacity
        Map<String, Long> cap = capacity(playerId);

        // City status multiplier
        double stateMul = 1.0;
        String cityStatus = getCityStatusAt(playerId, Math.max(lastTick, now - Math.round(dt * 1000)));
        if ("war".equals(cityStatus)) stateMul = 0.7;
        if ("shield".equals(cityStatus)) stateMul = 1.1;

        PlayerCity foodCity = cityScope.selected(playerId).orElse(null);
        // 新援军的抵达也由接收城推进，不能等派兵账号 Tick 后才启用粮账。
        if (foodCity != null) garrisonFood.settle(foodCity, now);
        boolean foodManaged = garrisonFood.managed(foodCity);
        if (foodManaged) garrisonFood.enforce(foodCity, now);

        // Get resources
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null) {
            res = new Resources();
            res.setPlayerId(playerId);
            res.setCitySlot(cityScope.slot(playerId));
            res.setFood(0);
            res.setSteel(0);
            res.setOil(0);
            res.setRare(0);
            res.setGold(0);
        }

        long food = res.getFood() != null ? res.getFood() : 0L;
        long steel = res.getSteel() != null ? res.getSteel() : 0L;
        long oil = res.getOil() != null ? res.getOil() : 0L;
        long rare = res.getRare() != null ? res.getRare() : 0L;
        long gold = res.getGold() != null ? res.getGold() : 0L;

        // 粮食与驻军账本共用连续净收支算法，容量只限制自然产出。
        if (buildingLevel(playerId, "refinery") > 0) {
            long steelCap = cap.get("steel");
            if (steel < steelCap) {
                steel = accrue(res, "steel", steel, produceOf(playerId, "refinery") * hours * stateMul, steelCap, false);
            }
        }
        if (buildingLevel(playerId, "oilfield") > 0) {
            long oilCap = cap.get("oil");
            if (oil < oilCap) {
                oil = accrue(res, "oil", oil, produceOf(playerId, "oilfield") * hours * stateMul, oilCap, false);
            }
        }
        if (buildingLevel(playerId, "raremine") > 0) {
            long rareCap = cap.get("rare");
            if (rare < rareCap) {
                rare = accrue(res, "rare", rare, produceOf(playerId, "raremine") * hours * stateMul, rareCap, false);
            }
        }

        // Food consumption - 受粮食保存科技及市长军屯技能降低
        int logFoodLevel = getTechLevel(playerId, "log_food");
        double foodSave = 1 - 0.05 * logFoodLevel;
        if (foodSave < 0.5) foodSave = 0.5;
        int rationLv = getOfficerSkillLevel(mayor, "ration");
        if (rationLv > 0) {
            foodSave *= (1.0 - 0.16 * rationLv);
        }
        if (foodSave < 0.1) foodSave = 0.1;
        double foodUse = foodPerHour(playerId) * foodSave * hours;
        if (!foodManaged) {
            double opening = food + res.getFoodAccrual();
            double after = settleBalance(opening, produceOf(playerId, "farm") * stateMul,
                    foodPerHour(playerId) * foodSave, hours, cap.get("food"));
            food = accrue(res, "food", food, after - opening, null, true);
        }

        // Resentment & Morale calculation
        int tax = cityScope.economy(playerId).getTax() != null ? cityScope.economy(playerId).getTax() : 30;
        int resentment = cityScope.economy(playerId).getResentment() != null ? cityScope.economy(playerId).getResentment() : 0;

        // 民怨与民心均按连续时长漂移，持久化小数，不在离线一小时处跳变。
        double resentmentStart = resentment + cityScope.economy(playerId).getResentmentAccrual();
        double moraleStart = Objects.requireNonNullElse(cityScope.economy(playerId).getMorale(), 70)
                + cityScope.economy(playerId).getMoraleAccrual();
        double resentmentRate = tax > 50 ? (tax - 50) * .05 : tax <= 20 ? -(25 - tax) * .05 : 0;
        double[] sentiment = settleSentiment(moraleStart, resentmentStart, tax, resentmentRate, hours);
        int morale = (int) Math.floor(sentiment[0] + 1e-9);
        resentment = (int) Math.floor(sentiment[1] + 1e-9);

        // 人口和税收同步积分；新增长人口只从出现时起纳税，逃亡也不按区间末人口追溯。
        int populationCap = popMax(playerId);
        int effectiveCap = (int) Math.round(PopulationEconomy.effectiveCap(populationCap, sentiment[0]));
        double openingPopulation = Math.max(0, Objects.requireNonNullElse(cityScope.economy(playerId).getCivilianPopulation(), 0)
                + Objects.requireNonNullElse(cityScope.economy(playerId).getPopulationGrowthRemainder(), 0.0));
        int financeLv = getOfficerSkillLevel(mayor, "finance");
        double taxFactor = taxFactor(tax, mayorKnow, .04 * financeLv);
        List<Officer> officers = officerRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        double totalSalary = officers.stream().mapToDouble(o -> Math.max(0, Objects.requireNonNullElse(o.getSalary(), 0))).sum();
        double goldBeforeIncome = gold + res.getGoldAccrual();
        PopulationEconomy.Result settled = PopulationEconomy.settle(openingPopulation, goldBeforeIncome, moraleStart,
                resentmentStart, tax, resentmentRate, populationCap, taxFactor, totalSalary, cap.get("gold"),
                now - Math.round(dt * 1000), now, res, officers);
        int civilians = (int) Math.floor(settled.population() + 1e-9);
        double populationRemainder = settled.population() - civilians;
        gold = accrue(res, "gold", gold, settled.gold() - goldBeforeIncome, null, true);

        // Save resources
        res.setFood(food);
        res.setSteel(steel);
        res.setOil(oil);
        res.setRare(rare);
        res.setGold(gold);
        resourcesRepository.save(res);

        // Save officers
        officerRepository.saveAll(officers);

        // Save player
        cityScope.economy(playerId).setMorale(morale);
        cityScope.economy(playerId).setResentment(resentment);
        cityScope.economy(playerId).setMoraleAccrual(sentiment[0] - morale);
        cityScope.economy(playerId).setResentmentAccrual(sentiment[1] - resentment);
        cityScope.economy(playerId).setCivilianPopulation(civilians);
        cityScope.economy(playerId).setPopulationGrowthRemainder(populationRemainder);
        cityScope.economy(playerId).setLastTick(now);
        playerRepository.save(player);
        cityScope.saveEconomy(playerId);

        // Update city state
        updateCityState(playerId, now);

        if (!processQueues) return;

        // Complete army production queues before processing marches.
        armyService.completeProduction(playerId, now);


        // Process marches
        marchService.processMarches(playerId, now);

        // Process incoming
        if (cityScope.slot(playerId) == 0) marchService.processIncoming(playerId, now);

        // Complete constructions and research
        List<String> completedBuilds = buildService.completeUpgrade(playerId, now);
        List<String> completedResearch = techService != null
                ? techService.settleCompletedResearchForCity(playerId, cityScope.slot(playerId), now)
                : Collections.emptyList();

        GarrisonFoodTimeline.Completions earlier = timeline.drain(playerId, cityScope.slot(playerId));
        completedBuilds = new ArrayList<>(new LinkedHashSet<>(concat(completedBuilds, earlier.buildsFor(cityScope.slot(playerId)))));
        completedResearch = new ArrayList<>(new LinkedHashSet<>(concat(completedResearch, earlier.researchFor(cityScope.slot(playerId)))));

        // Push tick update to player via WebSocket
        pushTickUpdate(playerId, res, now, completedBuilds, completedResearch, civilians, populationCap, effectiveCap, morale, resentment, tax);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first); result.addAll(second); return result;
    }

    /**
     * 构建 tick 更新数据并推送到玩家。
     * 包含: 资源、行军进度、建筑进度、科研进度；如本 tick 有建筑或科技刚刚完成，会附带对应 completed 列表。
     */
    private void pushTickUpdate(Long playerId, Resources res, long now, List<String> completedBuilds, List<String> completedResearch,
                                int civilians, int populationCap, int effectiveCap, int morale, int resentment, int tax) {
        Map<String, Object> stateChanges = buildStateChanges(playerId, res, now, completedBuilds, completedResearch,
                civilians, populationCap, effectiveCap, morale, resentment, tax);
        pushService.pushTickUpdate(playerId, stateChanges);
    }

    /**
     * 手动触发指定玩家的状态变更推送，并返回当前状态变更数据。
     */
    public Map<String, Object> pushStateChanges(Long playerId) {
        long now = GameClock.now();
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null) {
            res = resourcesRepository.findByPlayerId(playerId).orElseGet(Resources::new);
        }
        CityEconomy econ = cityScope.economy(playerId);
        int civilians = econ.getCivilianPopulation() != null ? econ.getCivilianPopulation() : 0;
        int populationCap = popMax(playerId);
        int effectiveCap = capacity(playerId).getOrDefault("civilianEffective", (long) populationCap).intValue();
        int morale = econ.getMorale() != null ? econ.getMorale() : 70;
        int resentment = econ.getResentment() != null ? econ.getResentment() : 0;
        int tax = econ.getTax() != null ? econ.getTax() : 0;
        Map<String, Object> stateChanges = buildStateChanges(playerId, res, now, Collections.emptyList(), Collections.emptyList(),
                civilians, populationCap, effectiveCap, morale, resentment, tax);
        pushService.pushTickUpdate(playerId, stateChanges);
        return stateChanges;
    }

    public Map<String, Object> buildStateChanges(Long playerId, Resources res, long now, List<String> completedBuilds,
                                                int civilians, int populationCap, int effectiveCap, int morale, int resentment, int tax) {
        return buildStateChanges(playerId, res, now, completedBuilds, Collections.emptyList(),
                civilians, populationCap, effectiveCap, morale, resentment, tax);
    }

    public Map<String, Object> buildStateChanges(Long playerId, Resources res, long now, List<String> completedBuilds, List<String> completedResearch,
                                                int civilians, int populationCap, int effectiveCap, int morale, int resentment, int tax) {
        Map<String, Object> stateChanges = new LinkedHashMap<>();
        // 账号科技可由其他城市完成，周期推送本城实时资格避免卡片保留旧锁态。
        stateChanges.put("unitUnlocks", unitUnlocks.all(playerId, cityScope.slot(playerId)));

        // 资源
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("food", res.getFood() != null ? res.getFood() : 0);
        resources.put("steel", res.getSteel() != null ? res.getSteel() : 0);
        resources.put("oil", res.getOil() != null ? res.getOil() : 0);
        resources.put("rare", res.getRare() != null ? res.getRare() : 0);
        resources.put("gold", res.getGold() != null ? res.getGold() : 0);
        stateChanges.put("resources", resources);
        stateChanges.put("resourceRates", resourceRates(playerId));
        if (techService != null) stateChanges.put("tech", techService.getTechMap(playerId));

        // 标量税率/民心/民怨
        stateChanges.put("tax", tax);
        stateChanges.put("morale", morale);
        stateChanges.put("resentment", resentment);

        // 人口：复用本 tick 已结算的最新值，避免 L1 cache 拿到旧数据
        Map<String, Object> population = new LinkedHashMap<>();
        population.put("civilian", civilians);
        population.put("capacity", populationCap);
        population.put("effectiveCapacity", effectiveCap);
        population.put("recruitable", civilians);
        double popGrowth = 0.0;
        if (civilians < effectiveCap) {
            popGrowth = populationCap * 0.03 * Math.max(0.2, morale / 70.0);
        } else if (civilians > effectiveCap) {
            double excess = civilians - effectiveCap;
            popGrowth = -Math.max(2.0, excess * 0.10);
        }
        population.put("growthPerHour", popGrowth);
        stateChanges.put("population", population);

        playerRepository.findById(playerId).ifPresent(player -> {
            stateChanges.put("incoming", worldViewService.getIncoming(player));
            stateChanges.put("returningEnemyMarches", worldViewService.getReturningEnemyMarches(player));
            // 跨过第7天、声望毕业或另一窗口退出后，及时同步账号所有城市的保护状态。
            stateChanges.put("newbieProtectionUntil", com.wargame.model.constants.NewbieProtection.until(player, now));
        });

        // 行军进度
        List<March> marches = marchRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        List<Map<String, Object>> marchList = new ArrayList<>();
        for (March m : marches) {
            marchList.add(worldViewService.toMarchMap(m, now));
        }
        stateChanges.put("marches", marchList);
        stateChanges.put("reinforcements", worldViewService.getReinforcements(playerId));
        // 接收／返程及交战伤亡后同步实际粮耗，不能只在整页刷新时更新。
        stateChanges.put("foodSupply", garrisonFood.supplyView(playerId));
        stateChanges.put("cityBattleLocked", cityBattleLock.locked(playerId));

        // 建筑进度
        List<Construction> constructions = constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        List<Map<String, Object>> buildList = new ArrayList<>();
        for (Construction c : constructions) {
            Map<String, Object> buildInfo = new LinkedHashMap<>();
            buildInfo.put("queueId", c.getId());
            buildInfo.put("id", c.getBuildingType());
            buildInfo.put("slot", c.getSlot());
            int targetLv = c.getTargetLevel() != null ? c.getTargetLevel() : 0;
            buildInfo.put("targetLevel", targetLv);
            buildInfo.put("startedAt", c.getStartAt() != null ? c.getStartAt() : 0);
            buildInfo.put("finishesAt", c.getFinishAt() != null ? c.getFinishAt() : 0);
            buildInfo.put("freeSpeedUpAt", c.getFinishAt() == null ? null : c.getFinishAt() - BuildService.FREE_SPEED_UP_SECONDS * 1000L);
            buildInfo.put("speedUpCounts", speedUpSupport.suggestedCounts(playerId, Objects.requireNonNullElse(c.getFinishAt(), 0L)));
            buildInfo.put("progress", calcProgress(
                    c.getStartAt() != null ? c.getStartAt() : now,
                    c.getFinishAt() != null ? c.getFinishAt() : now,
                    now));
            int curLv = buildService.buildingLevel(playerId, c.getBuildingType(), c.getSlot() != null ? c.getSlot() : 0);
            if (targetLv < curLv) {
                buildInfo.put("action", "dismantle");
                buildInfo.put("fromLevel", curLv);
            } else {
                buildInfo.put("action", "upgrade");
                buildInfo.put("fromLevel", Math.max(0, targetLv - 1));
            }
            buildList.add(buildInfo);
        }
        stateChanges.put("constructions", buildList);

        // 科技研发进度
        if (techService != null) {
            stateChanges.put("research", techService.getActiveResearch(playerId));
        }

        // 本 tick 内刚刚完成的建筑(每项推一次 BUILD_DONE 给前端)
        if (completedBuilds != null && !completedBuilds.isEmpty()) {
            stateChanges.put("completedBuilds", completedBuilds);
        }

        // 本 tick 内刚刚完成的科技
        if (completedResearch != null && !completedResearch.isEmpty()) {
            stateChanges.put("completedResearch", completedResearch);
            if (techService != null) {
                stateChanges.put("tech", techService.getTechMap(playerId));
            }
        }

        stateChanges.put("gameplay", gameplayViews.tick(playerId, stateChanges));
        return stateChanges;
    }

    /** 计算进度百分比 (0-100) */
    private static int calcProgress(long start, long end, long now) {
        if (end <= start) return 100;
        if (now <= start) return 0;
        if (now >= end) return 100;
        return (int) ((now - start) * 100 / (end - start));
    }

    // ================================================================
    // Helper methods - matching JS Core methods
    // ================================================================

    /**
     * 按连续净收支结算；库存超过自然产出上限时先停税支付工资，降至上限后才恢复税收。
     * 触及上限的时刻分段处理，拆分同一时段不会额外丢弃本可支付工资的税收。
     */
    public static double settleBalance(double opening, double incomePerHour, double salaryPerHour, double hours, long cap) {
        if (hours <= 0) return opening;
        if (opening > cap) {
            if (salaryPerHour <= 0) return opening;
            double untilCap = (opening - cap) / salaryPerHour;
            if (untilCap >= hours) return opening - salaryPerHour * hours;
            hours -= untilCap;
            opening = cap;
        }
        return Math.min(cap, opening + (incomePerHour - salaryPerHour) * hours);
    }

    /** 只读返回本城实际经济费率；军情警讯和新手保护本身不改变生产倍率。 */
    public Map<String, Object> resourceRates(Long owner) {
        Resources stock = resourcesRepository.findByPlayerIdAndCitySlot(owner, cityScope.slot(owner)).orElse(null);
        String status = getCityStatus(owner);
        double multiplier = "war".equals(status) ? .7 : "shield".equals(status) ? 1.1 : 1;
        Map<String, Double> income = new LinkedHashMap<>(), expense = new LinkedHashMap<>(), net = new LinkedHashMap<>();
        income.put("food", produceOf(owner, "farm") * multiplier);
        income.put("steel", produceOf(owner, "refinery") * multiplier);
        income.put("oil", produceOf(owner, "oilfield") * multiplier);
        income.put("rare", produceOf(owner, "raremine") * multiplier);
        Officer mayor = getOfficerByRole(owner, "mayor");
        double know = mayor == null ? 0 : equipmentService.attributes(mayor).knowledge();
        CityEconomy local = cityScope.economy(owner);
        double people = Math.max(0, Objects.requireNonNullElse(local.getCivilianPopulation(), 0)
                + Objects.requireNonNullElse(local.getPopulationGrowthRemainder(), 0.0));
        income.put("gold", people * taxFactor(Objects.requireNonNullElse(local.getTax(), 30), know, .04 * getOfficerSkillLevel(mayor, "finance")));
        Map<String, Double> supply = garrisonFood.supplyView(owner);
        double use = supply == null ? foodPerHour(owner) * Math.max(.1, Math.max(.5, 1 - .05 * getTechLevel(owner, "log_food"))
                * (1 - .16 * getOfficerSkillLevel(mayor, "ration"))) : supply.get("totalPerHour");
        expense.put("food", use);
        expense.put("gold", officerRepository.findByPlayerIdAndCitySlot(owner, cityScope.slot(owner)).stream()
                .mapToDouble(o -> Math.max(0, Objects.requireNonNullElse(o.getSalary(), 0))).sum());
        Map<String, Long> caps = capacity(owner);
        // 整数展示值可能刚好等于容量，经济判断仍须包含尚未入整的小数。
        double foodRemainder = cityScope.selected(owner).map(PlayerCity::getGarrisonFoodRemainder).orElse(0.0);
        Map<String, Double> amounts = stock == null ? Map.of() : Map.of(
                "food", Objects.requireNonNullElse(stock.getFood(), 0L) + stock.getFoodAccrual() + foodRemainder,
                "steel", Objects.requireNonNullElse(stock.getSteel(), 0L) + stock.getSteelAccrual(),
                "oil", Objects.requireNonNullElse(stock.getOil(), 0L) + stock.getOilAccrual(),
                "rare", Objects.requireNonNullElse(stock.getRare(), 0L) + stock.getRareAccrual(),
                "gold", Objects.requireNonNullElse(stock.getGold(), 0L) + stock.getGoldAccrual());
        boolean paused = cityScope.frozen(owner) || cityScope.selected(owner).map(c -> c.getReadyAt() > GameClock.now()).orElse(false);
        income.forEach((key, value) -> {
            double cost = expense.getOrDefault(key, 0.0), amount = amounts.getOrDefault(key, 0.0);
            double change = amount > caps.get(key) ? -cost : amount == caps.get(key) ? Math.min(0, value - cost) : value - cost;
            net.put(key, paused || amount <= 0 && change < 0 ? 0.0 : change);
        });
        if (paused) { income.replaceAll((key, value) -> 0.0); expense.replaceAll((key, value) -> 0.0); }
        return Map.of("netPerHour", net, "productionPerHour", income, "expensePerHour", expense, "status", status);
    }

    /** 连续目标随民怨线性移动；在触及目标或边界后分段，切分同一时段结果一致。 */
    public static double[] settleSentiment(double morale, double resentment, int tax, double rate, double hours) {
        double remaining = hours;
        while (remaining > 1e-10) {
            double step = remaining;
            if (rate > 0 && resentment < 100) step = Math.min(step, (100 - resentment) / rate);
            if (rate < 0 && resentment > 0) step = Math.min(step, -resentment / rate);
            double target = Math.max(0, Math.min(100, 100 - tax - resentment));
            double endResentment = Math.max(0, Math.min(100, resentment + rate * step));
            double endTarget = Math.max(0, Math.min(100, 100 - tax - endResentment));
            // 目标触及零点时另起一段，避免把截断后的目标误当成整段线性变化。
            if (rate > 0 && target > 0 && endTarget == 0) step = Math.min(step, target / rate);
            if (rate < 0 && resentment > 100 - tax && endResentment < 100 - tax)
                step = Math.min(step, (resentment - (100 - tax)) / -rate);
            endResentment = Math.max(0, Math.min(100, resentment + rate * step));
            endTarget = Math.max(0, Math.min(100, 100 - tax - endResentment));
            double slope = step > 0 ? (endTarget - target) / step : 0;
            double distance = target - morale;
            double meet = distance >= 0 ? distance / (10 - slope) : -distance / (10 + slope);
            morale = meet <= step ? endTarget : morale + Math.copySign(10 * step, distance);
            resentment = endResentment;
            remaining -= step;
            if (resentment <= 0 && rate < 0 || resentment >= 100 && rate > 0) rate = 0;
        }
        return new double[]{Math.max(0, Math.min(100, morale)), resentment};
    }

    /** 小数持久化保证频繁结算不吞产量或免粮耗；到达生产容量或零库存后不积累超产或欠款。 */
    private long accrue(Resources stock, String key, long current, double delta, Long cap, boolean rounded) {
        double carry = switch (key) {
            case "food" -> stock.getFoodAccrual(); case "steel" -> stock.getSteelAccrual();
            case "oil" -> stock.getOilAccrual(); case "rare" -> stock.getRareAccrual(); default -> stock.getGoldAccrual();
        };
        double exact = Math.max(0, current + carry + delta);
        if (cap != null) exact = Math.min(cap, exact);
        long amount = rounded ? Math.round(exact) : (long) Math.floor(exact + 1e-9);
        double remainder = Math.abs(exact - amount) < 1e-9 ? 0 : exact - amount;
        switch (key) {
            case "food" -> stock.setFoodAccrual(remainder); case "steel" -> stock.setSteelAccrual(remainder);
            case "oil" -> stock.setOilAccrual(remainder); case "rare" -> stock.setRareAccrual(remainder); default -> stock.setGoldAccrual(remainder);
        }
        return amount;
    }

    /**
     * produceOf(buildingType) - matches JS Core.produceOf
     * For each building of the type: curve = lv * (1 + 0.12 * (lv-1))
     * total = sum(baseProduce * curve)
     * return Math.floor(total * resBonusMul)
     */
    public static double taxFactor(int tax, double knowledge, double financeBonus) {
        return tax / 100.0 * (1 + knowledge / 100) * (1 + financeBonus) * 2;
    }

    /** 建筑逐级预览与真实自然产出共享基础曲线。 */
    public static double baseProduction(BuildingDef building, int level) {
        return Objects.requireNonNullElse(building.baseProduce(), 0) * level * (1 + .12 * (level - 1));
    }

    public double produceOf(Long playerId, String buildingType) {
        BuildingDef b = GameData.BUILDINGS.get(buildingType);
        if (b == null || b.produces() == null) return 0;

        List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), buildingType);
        double total = 0;
        for (Building building : buildings) {
            int lv = building.getLevel() != null ? building.getLevel() : 0;
            if (lv <= 0) continue;
            total += baseProduction(b, lv);
        }
        return Math.floor(total * resBonusMul(playerId, b.produces()));
    }

    /**
     * resBonusMul() - matches JS Core.resBonusMul
     * bonus = 1 + 0.05 * tech.prod_<resource> + mayor.logistics / 100
     * bonus *= 1 + 0.05 * mayor.harvest
     * bonus *= 1 + 0.03 * buildings.transit
     */
    /** 城内产量只读取当前资源对应科技；野地采集不使用此倍率。 */
    public double resBonusMul(Long playerId, String resource) {
        Officer mayor = getOfficerByRole(playerId, "mayor");
        double mayorLogi = mayor != null ? equipmentService.attributes(mayor).logistics() : 0;
        int harvestLv = getOfficerSkillLevel(mayor, "harvest");
        int logProduction = getTechLevel(playerId, "prod_" + resource);
        double bonus = 1 + 0.05 * logProduction + mayorLogi / 100.0;
        if (harvestLv > 0) {
            bonus *= (1.0 + 0.10 * harvestLv);
        }
        int transitLevel = buildingLevel(playerId, "transit");
        bonus *= 1 + 0.03 * transitLevel;
        return bonus;
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

    /**
     * foodPerHour() - matches JS Core.foodPerHour
     * sum of D.units[id].food * army[id] for each army unit
     */
    public long foodPerHour(Long playerId) {
        List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        long sum = 0;
        for (ArmyUnit unit : units) {
            UnitDef def = GameData.UNITS.get(unit.getType());
            if (def != null) {
                int count = unit.getCount() != null ? unit.getCount() : 0;
                sum += (long) def.food() * count;
            }
        }
        return sum;
    }

    /**
     * popMax() - matches JS Core.popMax
     * buildingLevel('house') * D.buildings.house.popPer
     */
    public int popMax(Long playerId) {
        int houseLevel = buildingLevel(playerId, "house");
        BuildingDef houseDef = GameData.BUILDINGS.get("house");
        int popPer = houseDef != null && houseDef.popPer() != null ? houseDef.popPer() : 1200;
        return houseLevel * popPer;
    }

    /**
     * capacity() - production capacity based on resource building levels
     * Each building level adds 200000 to the production capacity (Lv1=200k, Lv2=400k, ..., Lv10=2000k)
     * food -> farm, steel -> refinery, oil -> oilfield, rare -> raremine
     * gold = 20000000; tax income pauses at capacity without truncating existing stock
     */
    public Map<String, Long> capacity(Long playerId) {
        Map<String, Long> caps = new LinkedHashMap<>();
        caps.put("food", (long) buildingLevel(playerId, "farm") * 200000);
        caps.put("steel", (long) buildingLevel(playerId, "refinery") * 200000);
        caps.put("oil", (long) buildingLevel(playerId, "oilfield") * 200000);
        caps.put("rare", (long) buildingLevel(playerId, "raremine") * 200000);
        caps.put("gold", 20_000_000L);
        return caps;
    }

    /**
     * getCityStatus() - matches JS Core.getCityStatus
     * Returns "shield", "war", or "peace"
     */
    public String getCityStatus(Long playerId) {
        CityState cs = cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (cs == null) return "peace";

        long now = GameClock.now();

        return getCityStatusAt(playerId, now);
    }

    /** 读取不会清除历史边界；只有经济结算到达该时刻后才能清理到期状态。 */
    public String getCityStatusAt(Long playerId, long at) {
        CityState state = cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (state == null) return "peace";
        if (Objects.requireNonNullElse(state.getShieldUntil(), 0L) > at) return "shield";
        if ("war".equals(state.getStatus()) && (Objects.requireNonNullElse(state.getWarEndAt(), 0L) == 0
                || state.getWarEndAt() > at)) return "war";
        return "peace";
    }

    /**
     * getOfficerByRole() - matches JS Core.getOfficerByRole
     * Find first officer with the given role for the player
     */
    public Officer getOfficerByRole(Long playerId, String role) {
        List<Officer> officers = officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, cityScope.slot(playerId), role);
        if (officers != null && !officers.isEmpty()) {
            return officers.get(0);
        }
        return null;
    }

    /**
     * buildingLevel() - matches JS Core.buildingLevel
     * Sum of all building levels of the given type
     * (For multi-slot buildings, sums all slots; for single-slot, returns the level)
     */
    public int buildingLevel(Long playerId, String buildingType) {
        List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), buildingType);
        int sum = 0;
        for (Building b : buildings) {
            sum += b.getLevel() != null ? b.getLevel() : 0;
        }
        return sum;
    }

    /**
     * updateCityState() - matches JS Core.updateCityState
     * Check war/shield/peace expiration
     */
    private void updateCityState(Long playerId, long now) {
        CityState cs = cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (cs == null) {
            cs = new CityState();
            cs.setPlayerId(playerId);
            cs.setCitySlot(cityScope.slot(playerId));
            cs.setStatus("peace");
            cs.setWarEndAt(0L);
            cs.setShieldUntil(0L);
            cs.setPeaceUntil(0L);
            cityStateRepository.save(cs);
            return;
        }

        long shieldUntil = cs.getShieldUntil() != null ? cs.getShieldUntil() : 0L;
        if (shieldUntil != 0 && now >= shieldUntil) {
            cs.setShieldUntil(0L);
        }

        long warEndAt = cs.getWarEndAt() != null ? cs.getWarEndAt() : 0L;
        if ("war".equals(cs.getStatus()) && warEndAt != 0 && now >= warEndAt) {
            cs.setStatus("peace");
            cs.setWarEndAt(0L);
            cs.setPeaceUntil(now + 2 * 3600 * 1000L);
        }

        cityStateRepository.save(cs);
    }

    // ================================================================
    // Utility methods
    // ================================================================

    private int getTechLevel(Long playerId, String techType) {
        List<Technology> techs = technologyRepository.findByPlayerIdAndType(playerId, techType);
        if (techs != null && !techs.isEmpty()) {
            return techs.get(0).getLevel() != null ? techs.get(0).getLevel() : 0;
        }
        return 0;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
