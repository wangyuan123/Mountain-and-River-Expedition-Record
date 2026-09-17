package com.wargame.service;

import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TickService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    private static final Logger log = LoggerFactory.getLogger(TickService.class);

    /** Bankruptcy protection: gold floor (can be revoked by future feature). */
    private static final int GOLD_FLOOR = 0;
    /** Consecutive ticks a player can sit at the floor before officers start quitting. */
    private static final int BANKRUPTCY_GRACE_TICKS = 12;  // ~1 min @ 5s tick

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


    /** Per-player consecutive tick count at gold floor; resets when player earns gold. */
    private final ConcurrentHashMap<String, AtomicLong> bankruptTicks = new ConcurrentHashMap<>();

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
        this.marchRepository = marchRepository;
        this.constructionRepository = constructionRepository;
        this.pushService = pushService;
        this.worldViewService = worldViewService;
    }

    // ================================================================
    // tick - Process a single player's tick
    // ================================================================

    @org.springframework.beans.factory.annotation.Autowired private PlayerCityRepository playerCities;

    @Transactional
    public void tick(Long playerId) {
        Player current = playerRepository.lockById(playerId).orElse(null);
        // 恢复期继续结算；到期后绝不能补建已经清除的个人资产。
        if (current == null || current.deletionDue(System.currentTimeMillis())) return;
        // Each city settles its own clock and queues. Slot zero also advances the scheduler's account clock.
        try (var ignored = cityScope.enter(playerId, 0)) { tickCity(playerId); }
        for (PlayerCity city : playerCities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId)) {
            if (city.getCitySlot() == 0 || city.getReadyAt() > System.currentTimeMillis()) continue;
            try (var ignored = cityScope.enter(city)) { tickCity(playerId); }
        }
    }

    private void tickCity(Long playerId) {
        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null) return;

        long now = System.currentTimeMillis();
        long lastTick = cityScope.economy(playerId).getLastTick() != null ? cityScope.economy(playerId).getLastTick() : now;
        double dt = (now - lastTick) / 1000.0;
        if (dt <= 0) return;
        dt = Math.min(dt, Math.max(1, maxOfflineHours) * 3600.0);
        double hours = dt / 3600.0;

        // Get mayor
        Officer mayor = getOfficerByRole(playerId, "mayor");
        double mayorKnow = mayor != null && mayor.getKnowledge() != null ? mayor.getKnowledge() : 0;

        // Get capacity
        Map<String, Long> cap = capacity(playerId);

        // City status multiplier
        double stateMul = 1.0;
        String cityStatus = getCityStatus(playerId);
        if ("war".equals(cityStatus)) stateMul = 0.7;
        if ("shield".equals(cityStatus)) stateMul = 1.1;

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

        int food = res.getFood() != null ? res.getFood() : 0;
        int steel = res.getSteel() != null ? res.getSteel() : 0;
        int oil = res.getOil() != null ? res.getOil() : 0;
        int rare = res.getRare() != null ? res.getRare() : 0;
        int gold = res.getGold() != null ? res.getGold() : 0;

        // Resource production - cap only limits production, does not truncate existing stock
        if (buildingLevel(playerId, "farm") > 0) {
            long foodCap = cap.get("food");
            if (food < foodCap) {
                food = (int) Math.min(foodCap, food + produceOf(playerId, "farm") * hours * stateMul);
            }
        }
        if (buildingLevel(playerId, "refinery") > 0) {
            long steelCap = cap.get("steel");
            if (steel < steelCap) {
                steel = (int) Math.min(steelCap, steel + produceOf(playerId, "refinery") * hours * stateMul);
            }
        }
        if (buildingLevel(playerId, "oilfield") > 0) {
            long oilCap = cap.get("oil");
            if (oil < oilCap) {
                oil = (int) Math.min(oilCap, oil + produceOf(playerId, "oilfield") * hours * stateMul);
            }
        }
        if (buildingLevel(playerId, "raremine") > 0) {
            long rareCap = cap.get("rare");
            if (rare < rareCap) {
                rare = (int) Math.min(rareCap, rare + produceOf(playerId, "raremine") * hours * stateMul);
            }
        }

        // Food consumption
        int logFoodLevel = getTechLevel(playerId, "log_food");
        double foodSave = 1 - 0.05 * logFoodLevel;
        if (foodSave < 0.5) foodSave = 0.5;
        double foodUse = foodPerHour(playerId) * foodSave * hours;
        food = (int) Math.round(Math.max(0, food - foodUse));

        // Resentment & Morale calculation
        int tax = cityScope.economy(playerId).getTax() != null ? cityScope.economy(playerId).getTax() : 30;
        int resentment = cityScope.economy(playerId).getResentment() != null ? cityScope.economy(playerId).getResentment() : 0;

        // High tax (>50) generates resentment; low tax (<=20) dissipates resentment
        if (tax > 50) {
            double resGain = (tax - 50) * 0.05 * hours;
            if (resGain >= 1.0 || ThreadLocalRandom.current().nextDouble() < resGain) {
                resentment = Math.min(100, resentment + Math.max(1, (int) Math.floor(resGain)));
            }
        } else if (tax <= 20 && resentment > 0) {
            double resRelief = (25 - tax) * 0.05 * hours;
            if (resRelief >= 1.0 || ThreadLocalRandom.current().nextDouble() < resRelief) {
                resentment = Math.max(0, resentment - Math.max(1, (int) Math.floor(resRelief)));
            }
        }

        int targetMorale = clamp(100 - tax - resentment, 0, 100);
        int currentMorale = cityScope.economy(playerId).getMorale() != null ? cityScope.economy(playerId).getMorale() : 70;
        int morale = currentMorale;

        if (hours >= 1.0) {
            morale = targetMorale;
        } else if (currentMorale != targetMorale) {
            double driftRate = 10.0 * hours; // ~10 points per hour drift towards target
            if (driftRate >= 1.0) {
                int step = (int) Math.floor(driftRate);
                morale = targetMorale > currentMorale ? Math.min(targetMorale, currentMorale + step)
                                                      : Math.max(targetMorale, currentMorale - step);
            } else if (ThreadLocalRandom.current().nextDouble() < driftRate) {
                morale += (targetMorale > currentMorale ? 1 : -1);
            }
        }

        // 平民按民居容量与民心自然增长/逃亡，小数部分累计到下一次结算。
        int populationCap = popMax(playerId);
        int effectiveCap = populationCap <= 0 ? 0 : Math.max(10, (int) Math.round(populationCap * Math.min(1.0, morale / 70.0)));
        int civilians = Math.max(0, cityScope.economy(playerId).getCivilianPopulation() != null ? cityScope.economy(playerId).getCivilianPopulation() : 0);
        double populationRemainder = cityScope.economy(playerId).getPopulationGrowthRemainder() != null ? cityScope.economy(playerId).getPopulationGrowthRemainder() : 0.0;
        if (civilians < effectiveCap) {
            double growthRateMultiplier = Math.max(0.2, morale / 70.0);
            double growth = populationCap * 0.03 * growthRateMultiplier * hours + populationRemainder;
            int gained = (int) Math.floor(growth);
            civilians = Math.min(effectiveCap, civilians + gained);
            populationRemainder = civilians >= effectiveCap ? 0.0 : growth - gained;
        } else if (civilians > effectiveCap) {
            double excess = civilians - effectiveCap;
            double fleePerHour = Math.max(2.0, excess * 0.10);
            double fleeAmount = fleePerHour * hours + populationRemainder;
            int lost = (int) Math.floor(fleeAmount);
            if (lost > 0) {
                civilians = Math.max(effectiveCap, civilians - lost);
                populationRemainder = civilians <= effectiveCap ? 0.0 : fleeAmount - lost;
            } else {
                populationRemainder = fleeAmount;
            }
        } else {
            populationRemainder = 0.0;
        }

        // 黄金税收以当前平民人口为基数，超过上限时不再继续增加。
        double goldRate = civilians * (tax / 100.0) * (1 + mayorKnow / 100.0) * 2;
        long goldCap = cap.get("gold");
        if (gold < goldCap) {
            gold = (int) Math.round(Math.min(goldCap, gold + goldRate * hours));
        }

        // Officer salary
        List<Officer> officers = officerRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        double totalSalary = 0;
        for (Officer off : officers) {
            totalSalary += (off.getSalary() != null ? off.getSalary() : 0);

            // Officer loyalty
            double loy = off.getLoyalty() != null ? off.getLoyalty() : 0;
            String role = off.getRole() != null ? off.getRole() : "idle";
            if ("idle".equals(role)) {
                loy = Math.min(100, loy + 0.5 * hours);
            } else {
                loy = Math.max(0, loy - 0.2 * hours);
            }
            off.setLoyalty((int) Math.round(loy));
        }
        int goldAfterSalary = (int) Math.round(gold - totalSalary * hours);
        // Bankruptcy protection: clamp at floor, and if we were already at floor, count down.
        if (goldAfterSalary < GOLD_FLOOR) {
            long consecutive = bankruptTicks
                .computeIfAbsent(playerId + ":" + cityScope.slot(playerId), k -> new AtomicLong(0))
                .incrementAndGet();
            if (consecutive >= BANKRUPTCY_GRACE_TICKS) {
                // Force officers to quit / morale to drop to force player to act.
                for (Officer off : officers) {
                    int loy = off.getLoyalty() != null ? off.getLoyalty() : 100;
                    off.setLoyalty(Math.max(0, loy - 2));
                }
            }
            gold = GOLD_FLOOR;
        } else {
            // Earning again: clear the counter
            bankruptTicks.computeIfPresent(playerId + ":" + cityScope.slot(playerId), (k, v) -> { v.set(0); return v; });
            gold = goldAfterSalary;
        }

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
        cityScope.economy(playerId).setCivilianPopulation(civilians);
        cityScope.economy(playerId).setPopulationGrowthRemainder(populationRemainder);
        cityScope.economy(playerId).setLastTick(now);
        playerRepository.save(player);
        cityScope.saveEconomy(playerId);

        // Update city state
        updateCityState(playerId, now);

        // Complete army production queues before processing marches.
        armyService.completeProduction(playerId, now);

        // Process marches
        marchService.processMarches(playerId, now);

        // Process incoming
        if (cityScope.slot(playerId) == 0) marchService.processIncoming(playerId, now);

        // Complete constructions
        List<String> completedBuilds = buildService.completeUpgrade(playerId, now);

        // Push tick update to player via WebSocket
        pushTickUpdate(playerId, res, now, completedBuilds, civilians, populationCap, effectiveCap, morale, resentment, tax);
    }

    /**
     * 构建 tick 更新数据并推送到玩家。
     * 包含: 资源、行军进度、建筑进度；如本 tick 有建筑刚刚完成，会附带 completedBuilds 列表，
     * 前端据此触发"建筑升级完成"事件(每日任务等需要等真完成才计数的逻辑)。
     */
    private void pushTickUpdate(Long playerId, Resources res, long now, List<String> completedBuilds,
                                int civilians, int populationCap, int effectiveCap, int morale, int resentment, int tax) {
        Map<String, Object> stateChanges = buildStateChanges(playerId, res, now, completedBuilds,
                civilians, populationCap, effectiveCap, morale, resentment, tax);
        pushService.pushTickUpdate(playerId, stateChanges);
    }

    /**
     * 手动触发指定玩家的状态变更推送，并返回当前状态变更数据。
     */
    public Map<String, Object> pushStateChanges(Long playerId) {
        long now = System.currentTimeMillis();
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
        Map<String, Object> stateChanges = buildStateChanges(playerId, res, now, Collections.emptyList(),
                civilians, populationCap, effectiveCap, morale, resentment, tax);
        pushService.pushTickUpdate(playerId, stateChanges);
        return stateChanges;
    }

    public Map<String, Object> buildStateChanges(Long playerId, Resources res, long now, List<String> completedBuilds,
                                                int civilians, int populationCap, int effectiveCap, int morale, int resentment, int tax) {
        Map<String, Object> stateChanges = new LinkedHashMap<>();

        // 资源
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("food", res.getFood() != null ? res.getFood() : 0);
        resources.put("steel", res.getSteel() != null ? res.getSteel() : 0);
        resources.put("oil", res.getOil() != null ? res.getOil() : 0);
        resources.put("rare", res.getRare() != null ? res.getRare() : 0);
        resources.put("gold", res.getGold() != null ? res.getGold() : 0);
        stateChanges.put("resources", resources);

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
        population.put("growthPerHour", populationCap * 0.03 * Math.max(0.2, morale / 70.0));
        stateChanges.put("population", population);

        playerRepository.findById(playerId).ifPresent(player ->
                stateChanges.put("incoming", worldViewService.getIncoming(player)));

        // 行军进度
        List<March> marches = marchRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        List<Map<String, Object>> marchList = new ArrayList<>();
        for (March m : marches) {
            marchList.add(worldViewService.toMarchMap(m, now));
        }
        stateChanges.put("marches", marchList);

        // 建筑进度
        List<Construction> constructions = constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        List<Map<String, Object>> buildList = new ArrayList<>();
        for (Construction c : constructions) {
            Map<String, Object> buildInfo = new LinkedHashMap<>();
            buildInfo.put("id", c.getBuildingType());
            buildInfo.put("slot", c.getSlot());
            int targetLv = c.getTargetLevel() != null ? c.getTargetLevel() : 0;
            buildInfo.put("targetLevel", targetLv);
            buildInfo.put("startedAt", c.getStartAt() != null ? c.getStartAt() : 0);
            buildInfo.put("finishesAt", c.getFinishAt() != null ? c.getFinishAt() : 0);
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

        // 本 tick 内刚刚完成的建筑(每项推一次 BUILD_DONE 给前端)
        if (completedBuilds != null && !completedBuilds.isEmpty()) {
            stateChanges.put("completedBuilds", completedBuilds);
        }

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
     * produceOf(buildingType) - matches JS Core.produceOf
     * For each building of the type: curve = lv * (1 + 0.12 * (lv-1))
     * total = sum(baseProduce * curve)
     * return Math.floor(total * resBonusMul)
     */
    public double produceOf(Long playerId, String buildingType) {
        BuildingDef b = GameData.BUILDINGS.get(buildingType);
        if (b == null || b.produces() == null) return 0;

        List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), buildingType);
        double total = 0;
        for (Building building : buildings) {
            int lv = building.getLevel() != null ? building.getLevel() : 0;
            if (lv <= 0) continue;
            double curve = lv * (1 + 0.12 * (lv - 1));
            total += b.baseProduce() * curve;
        }
        return Math.floor(total * resBonusMul(playerId));
    }

    /**
     * resBonusMul() - matches JS Core.resBonusMul
     * bonus = 1 + 0.05 * tech.log_production + mayor.logistics / 100
     * bonus *= 1 + 0.03 * buildings.transit
     */
    private double resBonusMul(Long playerId) {
        Officer mayor = getOfficerByRole(playerId, "mayor");
        double mayorLogi = mayor != null ? equipmentService.attributes(mayor).logistics() : 0;
        int logProduction = getTechLevel(playerId, "log_production");
        double bonus = 1 + 0.05 * logProduction + mayorLogi / 100.0;
        int transitLevel = buildingLevel(playerId, "transit");
        bonus *= 1 + 0.03 * transitLevel;
        return bonus;
    }

    /**
     * foodPerHour() - matches JS Core.foodPerHour
     * sum of D.units[id].food * army[id] for each army unit
     */
    public int foodPerHour(Long playerId) {
        List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        int sum = 0;
        for (ArmyUnit unit : units) {
            UnitDef def = GameData.UNITS.get(unit.getType());
            if (def != null) {
                int count = unit.getCount() != null ? unit.getCount() : 0;
                sum += def.food() * count;
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
     * capacity() - matches JS Core.capacity
     * For food/steel/oil/rare: floor((baseCap + depotLv * depot.capPer * capGrowth) * capMul)
     * capMul = 1 + 0.10 * tech.log_warehouse
     * gold = 999999
     */
    /**
     * capacity() - resource cap based on resource building levels
     * Each building level adds 200000 to the cap (Lv1=200k, Lv2=400k, ..., Lv10=2000k)
     * food -> farm, steel -> refinery, oil -> oilfield, rare -> raremine
     * gold = 999999
     */
    public Map<String, Long> capacity(Long playerId) {
        Map<String, Long> caps = new LinkedHashMap<>();
        caps.put("food", (long) buildingLevel(playerId, "farm") * 200000);
        caps.put("steel", (long) buildingLevel(playerId, "refinery") * 200000);
        caps.put("oil", (long) buildingLevel(playerId, "oilfield") * 200000);
        caps.put("rare", (long) buildingLevel(playerId, "raremine") * 200000);
        caps.put("gold", 999999L);
        return caps;
    }

    /**
     * getCityStatus() - matches JS Core.getCityStatus
     * Returns "shield", "war", or "peace"
     */
    public String getCityStatus(Long playerId) {
        CityState cs = cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (cs == null) return "peace";

        long now = System.currentTimeMillis();

        // updateCityState logic inline
        long shieldUntil = cs.getShieldUntil() != null ? cs.getShieldUntil() : 0L;
        if (shieldUntil != 0 && now >= shieldUntil) {
            cs.setShieldUntil(0L);
            cityStateRepository.save(cs);
        }

        long warEndAt = cs.getWarEndAt() != null ? cs.getWarEndAt() : 0L;
        if ("war".equals(cs.getStatus()) && warEndAt != 0 && now >= warEndAt) {
            cs.setStatus("peace");
            cs.setWarTargetId(null);
            cs.setWarEndAt(0L);
            cs.setPeaceUntil(now + 2 * 3600 * 1000L);
            cityStateRepository.save(cs);
        }

        // Re-read updated values
        shieldUntil = cs.getShieldUntil() != null ? cs.getShieldUntil() : 0L;
        if (shieldUntil != 0 && now < shieldUntil) return "shield";
        if ("war".equals(cs.getStatus())) return "war";
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
            cs.setWarTargetId(null);
            cs.setWarAt(0L);
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
            cs.setWarTargetId(null);
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
