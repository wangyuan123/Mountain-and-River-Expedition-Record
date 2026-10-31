package com.wargame.service;

import com.wargame.model.constants.*;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.ConquestWallState;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 仅提供权威展示数据；所有写操作仍在各业务服务的事务内重新计算，绝不接受客户端报价。 */
@Service
public class GameplayViewService {
    @Autowired @Lazy private BuildService builds;
    @Autowired @Lazy private TechService techs;
    @Autowired @Lazy private TickService economy;
    @Autowired @Lazy private ArmyService army;
    @Autowired @Lazy private FortService forts;
    @Autowired @Lazy private MarketService market;
    @Autowired @Lazy private MarchService marches;
    @Autowired @Lazy private VipService vip;
    @Autowired private PrerequisiteService prerequisites;
    @Autowired private CityScope scope;
    @Autowired private PlayerRepository players;
    @Autowired private OfficerRepository officers;
    @Autowired private EquipmentService equipment;
    @Autowired private WoundedService wounded;
    @Autowired private DepotService depot;
    @Autowired private MobilizationService mobilization;
    @Autowired private PlayerItemRepository inventory;
    @Autowired private ResourcesRepository resources;
    @Autowired private PlayerCityRepository cities;
    @Autowired private PrisonerService prisoners;
    @Autowired @Lazy private BattleActionPreferences battlePreferences;
    @Autowired private MarchRepository marchRepository;
    private Map<String, Object> catalogCache;

    public static final String VERSION = "server-gameplay-20261031";

    /** 与完整状态同一账号、同一城市；缺失快照时客户端等待同步，禁止重建公式。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot(Long playerId, Map<String, Object> state) {
        return snapshot(playerId, state, true);
    }

    /** 周期推送不重复传输静态目录，但所有随资源、科技和任命变化的预览都刷新。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot(Long playerId, Map<String, Object> state, boolean includeCatalog) {
        Player player = players.findById(playerId).orElseThrow();
        int slot = scope.slot(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", VERSION); result.put("citySlot", slot); result.put("serverTime", GameClock.now());
        result.put("capacity", economy.capacity(playerId));
        result.put("inventoryCapacity", Map.of("food", ResourceOverflowService.STOCK_LIMIT, "steel", ResourceOverflowService.STOCK_LIMIT,
                "oil", ResourceOverflowService.STOCK_LIMIT, "rare", ResourceOverflowService.STOCK_LIMIT, "gold", ResourceOverflowService.STOCK_LIMIT));
        result.put("buildings", builds.previewLevels(playerId));
        result.put("technologies", techs.previewLevels(playerId));
        result.put("prerequisites", prerequisites.evaluated(playerId, slot));
        result.put("maxConcurrent", vip.getBuildQueueLimit(player));
        result.put("groupSlots", builds.groupSlotsView(playerId));
        result.put("armyCap", army.armyCap(playerId)); result.put("ordinaryArmyCap", army.ordinaryArmyCap(playerId));
        result.put("maxMarches", marches.getMaxMarches(playerId, slot));
        result.put("battleDefaultsLocked", battlePreferences.isLocked(playerId));
        result.put("campaignAccess", campaignAccess());
        result.put("pvpAllowed", Objects.requireNonNullElse(player.getPrestige(), 0) >= GameConstants.PVP_PRESTIGE_THRESHOLD);
        result.put("buildDiscountPercent", Math.round((1 - builds.buildMul(playerId)) * 100));
        var commander = officers.findByPlayerIdAndCitySlotAndRole(playerId, slot, "commander").stream().findFirst().orElse(null);
        result.put("leadershipPercent", commander == null ? 0 : skillBonus(JsonUtil.parseList(commander.getSkills()), "leadership") * 100);
        result.put("militaryPopulation", ((Map<String, Object>) state.get("army")).entrySet().stream().mapToLong(e -> ((Number) e.getValue()).longValue() * (UnitDef.UNITS.containsKey(e.getKey()) ? UnitDef.UNITS.get(e.getKey()).pop() : 0)).sum());
        result.put("fortCap", forts.fortCap(playerId)); result.put("fortAdmissionCap", forts.admissionCap(playerId));
        result.put("protectCap", builds.buildingLevel(playerId, "depot") * BuildingDef.BUILDINGS.get("depot").protectPer()
                * (1 + .05 * techs.getTechLevel(playerId, "log_warehouse")));
        result.put("wall", ConquestWallState.create(builds.buildingLevel(playerId, "wall"),
                techs.getTechLevel(playerId, "wall_reinforcement"), false, VERSION));
        Map<String, Object> unitViews = new LinkedHashMap<>(), training = new LinkedHashMap<>();
        UnitDef.UNITS.forEach((id, def) -> {
            unitViews.put(id, army.previewRecruit(playerId, id, 1));
            training.computeIfAbsent(def.build(), key -> army.trainingStats(playerId, key));
        });
        result.put("units", unitViews); result.put("training", training);
        Map<String, Object> production = new LinkedHashMap<>(), buildingEffects = new LinkedHashMap<>();
        int wallTech = techs.getTechLevel(playerId, "wall_reinforcement"), warehouseTech = techs.getTechLevel(playerId, "log_warehouse");
        BuildingDef.BUILDINGS.forEach((id, definition) -> {
            List<Map<String, Object>> levels = new ArrayList<>();
            double bonus = definition.produces() == null ? 1 : economy.resBonusMul(playerId, definition.produces());
            if (definition.produces() != null) production.put(id, economy.produceOf(playerId, id));
            for (int level = 0; level <= 10; level++) {
                var wall = ConquestWallState.create(level, wallTech, false, VERSION);
                Map<String, Object> effect = new LinkedHashMap<>();
                effect.put("production", definition.produces() == null ? 0 : Math.floor(TickService.baseProduction(definition, level) * bonus));
                effect.put("capacity", level * 200000L); effect.put("prisonerCapacity", level * 2000); effect.put("wildCap", WildOccupationService.BASE_CAP + level); effect.put("population", level * Objects.requireNonNullElse(definition.popPer(), 0));
                effect.put("protection", (int) Math.floor(level * Objects.requireNonNullElse(definition.protectPer(), 0) * (1 + .05 * warehouseTech)));
                effect.put("wallHp", wall.maxHp()); effect.put("wallDefense", wall.defense());
                effect.put("fiveStarPercent", OfficerService.academyFiveStarBatchChance(level) * 100);
                effect.put("taxPercent", market.getTaxRate(level) * 100);
                effect.put("exchangeRate", market.getExchangeRate(level));
                effect.put("fortCapacity", level * 3000); effect.put("airSpeedPercent", level * Objects.requireNonNullElse(definition.airSpdBonus(), 0));
                effect.put("resourceBonusPercent", level * Objects.requireNonNullElse(definition.resBonus(), 0));
                effect.put("marketSlots", MarketService.orderSlotCap(level));
                effect.put("maxMarches", 5 + level);
                levels.add(effect);
            }
            buildingEffects.put(id, levels);
        });
        List<Map<String, Object>> access = new ArrayList<>();
        for (int lv = 0; lv <= 10; lv++) {
            int level = lv;
            access.add(Map.of("level", level, "unlocked", TechDef.TECHS.values().stream().filter(t -> t.labReq() <= level).map(TechDef::key).toList(),
                    "newlyUnlocked", TechDef.TECHS.values().stream().filter(t -> level < 10 && t.labReq() == level + 1).map(TechDef::key).toList()));
        }
        result.put("researchAccess", access);
        result.put("production", production); result.put("buildingEffects", buildingEffects);
        Map<String, Integer> tech = new LinkedHashMap<>();
        ((Map<String, Object>) state.get("tech")).forEach((key, value) -> tech.put(key, ((Number) value).intValue()));
        Map<String, Object> multipliers = new LinkedHashMap<>();
        multipliers.put("firepower", TechnologyRules.multiplier(tech, "fire_density"));
        multipliers.put("load", TechnologyRules.multiplier(tech, "log_load"));
        multipliers.put("medical", Math.min(.5, .05 * tech.getOrDefault("log_medical", 0)));
        result.put("multipliers", multipliers);
        result.put("mobilization", mobilization.view(playerId));
        result.put("economy", economyPreview(playerId, state));
        Map<String, Object> catalog = catalog();
        if (includeCatalog) result.put("catalog", catalog);
        result.put("shop", shopViews(playerId));
        result.put("shieldQuotes", shieldQuotes(playerId));
        Set<Long> busyOfficers = new HashSet<>();
        marchRepository.findByPlayerId(playerId).forEach(march -> busyOfficers.add(march.getCommanderId()));
        for (var officer : (List<Map<String, Object>>) state.get("officers")) {
            enrichOfficer(officer);
            String reason = OfficerService.dispatchReason((String) officer.get("role"),
                    busyOfficers.contains(((Number) officer.get("id")).longValue()));
            officer.put("dispatch", Map.of("allowed", reason.isEmpty(), "reason", reason));
        }
        Map<String, Object> academy = (Map<String, Object>) state.get("academy");
        if (academy != null && academy.get("list") instanceof List<?> list) for (Object raw : list) {
            var candidate = (Map<String, Object>) raw;
            candidate.put("recruitCost", OfficerService.recruitCost(number(candidate, "star", 1)));
        }
        return result;
    }

    /** 税率滑块只选择服务端已计算的档位，所有收益和资格数据来自当前城市。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> economyPreview(Long playerId, Map<String, Object> state) {
        var population = (Map<String, Object>) state.get("population");
        int cap = number(population, "capacity", 0), civilians = number(population, "civilian", 0);
        var mayor = officers.findByPlayerIdAndCitySlotAndRole(playerId, scope.slot(playerId), "mayor").stream().findFirst().orElse(null);
        double knowledge = mayor == null ? 0 : equipment.attributes(mayor).knowledge();
        double finance = skillBonus(mayor == null ? List.of() : JsonUtil.parseList(mayor.getSkills()), "finance");
        List<Map<String, Object>> taxes = new ArrayList<>();
        for (int tax = 0; tax <= 100; tax++) {
            double morale = Math.max(0, Math.min(100, 100 - tax - number(state, "resentment", 0)));
            taxes.add(Map.of("goldPerHour", Math.round(civilians * TickService.taxFactor(tax, knowledge, finance)),
                    "targetMorale", morale, "effectiveCapacity", Math.round(PopulationEconomy.effectiveCap(cap, morale)),
                    "warning", tax > 50 ? "heavy" : tax <= 20 ? "light" : "normal"));
        }
        return Map.of("taxes", taxes, "appeaseGoldCost", GameStateService.appeaseGoldCost(civilians),
                "populationOrderAdded", DepotService.populationOrderAdded(cap, civilians, false),
                "emergencyPopulationOrderAdded", DepotService.populationOrderAdded(cap, civilians, true));
    }

    /** 军官资格、经验曲线和加点空间全部由服务端下发。 */
    @SuppressWarnings("unchecked")
    private void enrichOfficer(Map<String, Object> officer) {
        int level = number(officer, "level", 1);
        Map<String, Object> chief = new LinkedHashMap<>();
        OfficerEquipmentDef.BRANCHES.forEach(key -> chief.put(key, number(officer, key, 0) * CommandBonus.RATE * 100));
        officer.put("chiefPercents", chief);
        officer.put("researchKnowledgePercent", number(officer, "knowledge", 0) * TechnologyRules.RESEARCH_KNOWLEDGE_SPEED_PER_POINT * 100);
        officer.put("expNeeded", OfficerExperienceRules.expNeeded(level));
        var growth = OfficerExperienceRules.preview(level, ((Number) officer.get("exp")).longValue());
        officer.put("availableUpgradeCount", growth.upgraded());
        officer.put("rewardCost", OfficerService.rewardCost(number(officer, "star", 1), level));
        List<Map<String, Object>> skills = (List<Map<String, Object>>) officer.get("skills");
        Map<String, Object> learning = new LinkedHashMap<>(), bonuses = new LinkedHashMap<>(), assignable = new LinkedHashMap<>();
        OfficerSkillDef.OFFICER_SKILLS.forEach((id, definition) -> {
            String reason = OfficerService.skillLearningReason(skills, id);
            learning.put(id, Map.of("allowed", reason.isEmpty(), "reason", reason));
            bonuses.put(id, skillBonus(skills, id));
        });
        OfficerEquipmentDef.BRANCHES.forEach(key -> assignable.put(key, Math.max(0,
                Math.min(number(officer, "attrPoints", 0), OfficerService.ATTR_MAX - number(officer, "base" + Character.toUpperCase(key.charAt(0)) + key.substring(1), 0)))));
        officer.put("roleEffects", roleEffects(officer, bonuses));
        officer.put("skillLearning", learning); officer.put("skillBonuses", bonuses); officer.put("assignable", assignable);
    }

    /** 任命预览使用装备后属性和同一技能倍率；浏览器只渲染岗位行。 */
    private List<List<String>> roleEffects(Map<String, Object> officer, Map<String, Object> bonuses) {
        double military = number(officer, "military", 0), defense = number(officer, "defense", 0);
        double logistics = number(officer, "logistics", 0), knowledge = number(officer, "knowledge", 0);
        return List.of(
            List.of("市长", "四类资源基础产量加成 +" + logistics + "%；黄金税收 +" + knowledge
                + "%；科研速度 +" + knowledge * TechnologyRules.RESEARCH_KNOWLEDGE_SPEED_PER_POINT * 100
                + "%；养兵粮耗降低 " + ((Number) bonuses.get("ration")).doubleValue() * 100
                + "%；税收技能加成 +" + ((Number) bonuses.get("finance")).doubleValue() * 100 + "%。",
                "军事、防御和战斗技能不加成守军。内政技能仅在市长岗位生效。"),
            List.of("指挥官／随队将领", "周期回合攻击 +" + military + "%，防御 +" + defense
                + "%；任本城指挥官时，三军统帅提高单队出征上限 " + ((Number) bonuses.get("leadership")).doubleValue() * 100 + "%。",
                "攻防从首次接战E起，仅E、E+3、E+6等回合生效；各支援军独立起算。内政技能不生效。"),
            List.of("司令官", "常驻攻击 +" + military * CommandBonus.RATE * 100 + "%，防御 +" + defense * CommandBonus.RATE * 100
                + "%，生命 +" + logistics * CommandBonus.RATE * 100 + "%，机动 +" + knowledge * CommandBonus.RATE * 100 + "%。",
                "装备和套装计入；所有技能均不生效。不提高出征上限或大地图行军速度。"));
    }

    private double skillBonus(List<Map<String, Object>> skills, String id) {
        return skills.stream().anyMatch(s -> id.equals("supply".equals(s.get("id")) ? "leadership" : s.get("id")))
                ? OfficerSkillDef.bonus(id, OfficerSkillDef.OFFICER_SKILLS.get(id).max()) : 0;
    }

    /** 客户端提交意图；此接口不执行任何写操作，也不接受费用、收益或有效属性。 */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long playerId, Preview request) {
        if (request == null || request.action() == null || request.count() < 0 || request.count() > 1_000_000_000)
            throw new IllegalArgumentException("预览参数无效");
        if (Set.of("experience", "equipment", "heal").contains(request.action()) && request.objectId() == null)
            throw new IllegalArgumentException("预览对象不能为空");
        Map<String, Object> value = switch (request.action()) {
            case "warTiming" -> new LinkedHashMap<String, Object>(WorldService.warTiming(GameClock.now()));
            case "recruit" -> army.previewRecruit(playerId, request.id(), request.count());
            case "prisoner" -> prisoners.preview(playerId, request.id(), request.count());
            case "heal" -> wounded.preview(playerId, request.objectId(), request.count());
            case "experience" -> experiencePreview(playerId, request.objectId(), request.id(), request.count());
            case "equipment" -> equipment.preview(playerId, request.objectId(), request.keys());
            case "timedItem" -> depot.previewTimedItem(playerId, request.id());
            case "mobilization" -> mobilization.preview(playerId, request.id(), request.count());
            case "exchange" -> market.previewExchange(playerId, scope.slot(playerId), request.id(), request.target(), request.count());
            case "shop" -> shopPreview(playerId, request.id(), request.count());
            default -> throw new IllegalArgumentException("不支持的预览类型");
        };
        Map<String, Object> result = new LinkedHashMap<>(value);
        result.put("rulesVersion", VERSION); result.put("serverTime", GameClock.now());
        result.put("cityId", scope.selected(playerId).map(PlayerCity::getId).orElse(null));
        result.put("citySlot", scope.slot(playerId)); result.put("input", request);
        return result;
    }

    public record Preview(String action, String id, Long objectId, int count, String target, List<String> keys) {}

    private Map<String, Object> experiencePreview(Long player, Long id, String item, int count) {
        Officer officer = officers.findById(id).filter(o -> player.equals(o.getPlayerId()) && Objects.equals(scope.slot(player), o.getCitySlot()))
                .orElseThrow(() -> new IllegalArgumentException("军官不存在"));
        if (count < 1 || !ItemDef.EXPERIENCE_BOOK_EXP.containsKey(item) && !"expBookMax".equals(item)) throw new IllegalArgumentException("经验书参数无效");
        boolean max = "expBookMax".equals(item);
        long gain = max ? 0 : Math.multiplyExact((long) ItemDef.EXPERIENCE_BOOK_EXP.get(item), count);
        int level = Objects.requireNonNullElse(officer.getLevel(), 1);
        var growth = max ? new OfficerExperienceRules.Growth(100, 0, 100 - level)
                : OfficerExperienceRules.preview(level, Math.addExact(Objects.requireNonNullElse(officer.getExp(), 0L), gain));
        return Map.of("gain", gain, "level", growth.level(), "remainingExp", growth.exp(), "upgraded", growth.upgraded(),
                "expNeeded", OfficerExperienceRules.expNeeded(growth.level()), "points", growth.upgraded(),
                "neededForNext", Math.max(0, OfficerExperienceRules.expNeeded(growth.level()) - growth.exp()));
    }

    private Map<String, Object> shopPreview(Long playerId, String id, int count) {
        var player = players.findById(playerId).orElseThrow();
        Map<String, Integer> stock = new LinkedHashMap<>();
        inventory.findByPlayerId(playerId).forEach(item -> stock.put(item.getItemKey(), Objects.requireNonNullElse(item.getCount(), 0)));
        return shopQuote(player, Objects.requireNonNullElse(scope.wallet(playerId).getDiamond(), 0), stock, id, count);
    }

    /** 批量报价只读取一次钱包和库存；预览与购买共用限购和费用规则。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> shopViews(Long playerId) {
        var player = players.findById(playerId).orElseThrow();
        Map<String, Integer> stock = new LinkedHashMap<>();
        inventory.findByPlayerId(playerId).forEach(item -> stock.put(item.getItemKey(), Objects.requireNonNullElse(item.getCount(), 0)));
        long balance = Objects.requireNonNullElse(scope.wallet(playerId).getDiamond(), 0);
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<String, Object>) catalog().get("shopPrices")).forEach((id, price) -> result.put(id, shopQuote(player, balance, stock, id, 1)));
        return result;
    }

    private Map<String, Object> shopQuote(Player player, long balance, Map<String, Integer> stock, String id, int count) {
        Integer price = com.wargame.controller.ShopController.ShopItemPrices.getPrice(id);
        if (price == null) throw new IllegalArgumentException("商品无效");
        long cost = ShopRules.cost(price, count);
        String reason = ShopRules.unavailable(id, count, Objects.requireNonNullElse(player.getMilitaryRank(), 1),
                stock.getOrDefault(id, 0), stock.containsKey(id));
        boolean single = "newbiePack".equals(id) || MilitaryRankDef.certificateRank(id) != null;
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("unitPrice", price); quote.put("cost", cost); quote.put("affordable", balance >= cost);
        quote.put("allowed", reason.isEmpty() && balance >= cost); quote.put("reason", reason);
        quote.put("remaining", balance - cost);
        quote.put("maxAffordable", reason.isEmpty() ? Math.min(single ? 1 : 999, balance / Math.max(1, price)) : 0);
        quote.put("singlePurchase", single);
        quote.put("purchased", "newbiePack".equals(id) && stock.containsKey(id));
        quote.put("certificateReason", MilitaryRankDef.certificateRank(id) == null || reason.isEmpty() ? ""
                : reason.startsWith("已获得") ? "已获军衔" : reason.startsWith("已持有") ? "已持有" : reason);
        return quote;
    }

    private List<Map<String, Object>> shieldQuotes(Long playerId) {
        long diamond = Objects.requireNonNullElse(scope.wallet(playerId).getDiamond(), 0);
        int count = cities.findByOwnerId(playerId).size();
        return java.util.stream.IntStream.rangeClosed(0, count).mapToObj(n -> Map.<String, Object>of(
                "cost", CityService.shieldCost(n), "allowed", n > 0 && diamond >= CityService.shieldCost(n),
                "unitPrice", CityService.shieldCost(1))).toList();
    }

    /** 周期只推送随余额、人口和动员时间变化的值，避免逐秒重建全部等级目录。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> tick(Long playerId, Map<String, Object> state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverTime", GameClock.now());
        result.put("pvpAllowed", Objects.requireNonNullElse(players.findById(playerId).orElseThrow().getPrestige(), 0) >= GameConstants.PVP_PRESTIGE_THRESHOLD);
        result.put("armyCap", army.armyCap(playerId));
        result.put("battleDefaultsLocked", battlePreferences.isLocked(playerId));
        result.put("ordinaryArmyCap", army.ordinaryArmyCap(playerId));
        result.put("mobilization", mobilization.view(playerId));
        result.put("economy", economyPreview(playerId, state));
        result.put("shop", shopViews(playerId));
        result.put("shieldQuotes", shieldQuotes(playerId));
        Resources stock = resources.findByPlayerIdAndCitySlot(playerId, scope.slot(playerId)).orElse(null);
        int civilians = number((Map<String, Object>) state.get("population"), "civilian", 0);
        Map<String, UnitUnlockService.Result> unlocks = (Map<String, UnitUnlockService.Result>) state.get("unitUnlocks");
        Map<String, Object> limits = new LinkedHashMap<>();
        UnitDef.UNITS.forEach((id, unit) -> limits.put(id, unlocks.get(id).eligible() ? army.recruitLimit(unit, stock, civilians) : 0));
        result.put("recruitLimits", limits);
        return result;
    }

    /** 装备、道具、商品目录只在后端定义数值，前端仅保留图标映射。 */
    public synchronized Map<String, Object> catalog() {
        if (catalogCache != null) return catalogCache;
        Map<String, Object> items = new LinkedHashMap<>(), sets = new LinkedHashMap<>(), shop = new LinkedHashMap<>();
        OfficerEquipmentDef.ITEMS.forEach((key, d) -> {
            String tier = d.tierConfig().path("key").asText();
            items.put(key, Map.ofEntries(Map.entry("id", key), Map.entry("name", d.name()), Map.entry("cat", "equipment"),
                    Map.entry("tier", tier), Map.entry("branch", d.branch()), Map.entry("slot", d.slot()),
                    Map.entry("slotName", OfficerEquipmentDef.CONFIG.path("slots").get(OfficerEquipmentDef.SLOTS.indexOf(d.slot())).path("name").asText()),
                    Map.entry("level", d.requiredLevel()), Map.entry("setKey", d.setKey()), Map.entry("desc", d.description()),
                    Map.entry("attrs", Map.of("military", d.military(), "defense", d.defense(), "logistics", d.logistics(), "knowledge", d.knowledge()))));
            sets.computeIfAbsent(d.setKey(), set -> Map.of("id", set, "name", d.setName(), "tier", tier,
                    "branch", d.branch(), "level", d.requiredLevel(), "config", d.tierConfig(),
                    "keys", OfficerEquipmentDef.setItems(set).stream().map(OfficerEquipmentDef::key).toList()));
            shop.put("box_" + d.setKey(), OfficerEquipmentDef.boxPrice("box_" + d.setKey()));
        });
        shop.putAll(com.wargame.controller.ShopController.ShopItemPrices.PRICES);
        for (var rank : MilitaryRankDef.RANKS) if (rank.tier() > 1) shop.put(rank.certificateKey(), rank.certificatePrice());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("equipmentConfig", OfficerEquipmentDef.CONFIG); result.put("equipmentItems", items); result.put("equipmentSets", sets);
        result.put("shopPrices", shop); result.put("attrMax", OfficerService.ATTR_MAX); result.put("officerMaxLevel", OfficerExperienceRules.MAX_LEVEL);
        result.put("experience", java.util.stream.IntStream.rangeClosed(0, 100).mapToObj(OfficerExperienceRules::expNeeded).toList());
        result.put("campaigns", campaigns());
        result.put("experienceBooks", ItemDef.EXPERIENCE_BOOK_EXP);
        result.put("starUpFailurePercents", DepotService.starUpFailurePercents());
        result.put("timedItems", java.util.stream.Stream.concat(ItemDef.SHIELD_HOURS.keySet().stream(),
                ItemDef.MARCH_BONUS_PERCENT.keySet().stream()).toList());
        result.put("items", ItemDef.ITEMS); result.put("ranks", MilitaryRankDef.RANKS.stream().map(rank -> Map.of("tier", rank.tier(), "name", rank.name(),
                "prestige", rank.prestige(), "baseCap", rank.baseCap(), "npcVictories", rank.npcVictories(), "certificatePrice", rank.certificatePrice(), "cityCap", MilitaryRankDef.getCityCap(rank.tier()))).toList()); result.put("forts", FortDef.FORTS);
        Map<String, Object> motion = new LinkedHashMap<>();
        UnitDef.UNITS.forEach((id, unit) -> motion.put(id, Map.of("battleDistance", unit.spd() * 50,
                "marchSecondsPerGrid", WorldConfig.MARCH_SEC_PER_GRID / Math.max(1.0, unit.spd()))));
        FortDef.FORTS.forEach((id, fort) -> motion.put(id, Map.of("battleDistance", 0)));
        result.put("unitMotion", motion);
        result.put("roleRates", Map.of("chiefPercentPerPoint", CommandBonus.RATE * 100,
                "researchPercentPerPoint", TechnologyRules.RESEARCH_KNOWLEDGE_SPEED_PER_POINT * 100));
        Map<String, String> efficiency = new LinkedHashMap<>();
        shop.forEach((id, rawPrice) -> {
            ItemDef item = ItemDef.ITEMS.get(id);
            double hours = item != null && item.speedUpSeconds() > 0 ? item.speedUpSeconds() / 3600.0
                    : ItemDef.SHIELD_HOURS.getOrDefault(id, 0);
            if (hours <= 0) return;
            boolean speed = item != null && item.speedUpSeconds() > 0;
            double hourly = ((Number) rawPrice).doubleValue() / hours;
            String text = "折合 " + java.math.BigDecimal.valueOf(hourly).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "钻/时";
            if (hours >= (speed ? 5 : 8)) text += " · 省" + Math.round((1 - hourly / (speed ? 100 : 30)) * 100) + "%";
            efficiency.put(id, text);
        });
        result.put("shopEfficiency", efficiency);
        result.put("units", UnitDef.UNITS); result.put("buildings", BuildingDef.BUILDINGS); result.put("technologies", TechDef.TECHS);
        Map<String, Object> skills = new LinkedHashMap<>();
        OfficerSkillDef.OFFICER_SKILLS.forEach((key, skill) -> skills.put(key, Map.of("name", skill.name(), "max", skill.max(), "cat", skill.cat(), "desc", skill.descriptionAtLevel(skill.max()))));
        result.put("skills", skills);
        catalogCache = Collections.unmodifiableMap(result);
        return catalogCache;
    }

    private static final Map<String, Object> CAMPAIGNS = loadCampaigns();
    private static Map<String, Object> campaigns() { return CAMPAIGNS; }

    /** 旧战役没有执行或进度存储接口，只可浏览；不能在浏览器声称可挑战、解锁或发奖。 */
    private static Map<String, Object> campaignAccess() {
        Map<String, Object> result = new LinkedHashMap<>();
        campaigns().keySet().forEach(id -> {
            result.put(id, Map.of("allowed", false, "reason", "请通过世界地图出征，旧战役仅供浏览"));
        });
        return result;
    }
    private static Map<String, Object> loadCampaigns() {
        try (var input = GameplayViewService.class.getResourceAsStream("/game/campaign-catalog.json")) {
            return JsonUtil.getMapper().readValue(input, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (java.io.IOException e) { throw new IllegalStateException("战役目录无法加载", e); }
    }

    private static int number(Map<String, Object> map, String key, int fallback) {
        return map.get(key) instanceof Number value ? value.intValue() : fallback;
    }
}
