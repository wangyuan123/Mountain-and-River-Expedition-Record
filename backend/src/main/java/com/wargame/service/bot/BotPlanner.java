package com.wargame.service.bot;

import com.wargame.model.constants.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 决策只读自己的经济、公开地图和本人的有效侦查报告，不读取敌军实时库存。 */
@Service
@RequiredArgsConstructor
public class BotPlanner {
    private final BuildingRepository buildings;
    private final ConstructionRepository constructions;
    private final ArmyUnitRepository armies;
    private final ArmyProductionQueueRepository production;
    private final TechResearchQueueRepository research;
    private final WildTileRepository wilds;
    private final MarchRepository marches;
    private final ScoutReportRepository reports;
    private final PlayerRepository players;
    private final BuildService build;
    private final TechService tech;
    private final TickService ticks;
    private final PrerequisiteService prerequisites;
    private final UnitUnlockService unlocks;
    private final WorldViewService worldView;
    private final WorldService world;
    private final MarchService marchService;
    private final WildOccupationService occupation;
    private final CityScope cityScope;
    private final PlayerItemRepository items;

    public BotAction plan(BotStore.Profile profile, Player player, PlayerCity city, Resources wallet, long now) {
        long playerId = profile.playerId();
        int slot = profile.settings().citySlot();
        Map<String, Integer> levels = new HashMap<>();
        buildings.findByPlayerIdAndCitySlot(playerId, slot).forEach(row -> levels.merge(row.getType(), Objects.requireNonNullElse(row.getLevel(), 0), Math::max));
        Map<String, Integer> inventory = new LinkedHashMap<>();
        armies.findByPlayerIdAndCitySlot(playerId, slot).forEach(row -> inventory.merge(row.getType(), Objects.requireNonNullElse(row.getCount(), 0), Integer::sum));
        List<March> active = marches.findByPlayerIdAndCitySlot(playerId, slot);
        List<WildTile> owned = wilds.findByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(playerId, slot);
        BotAction gather = gathering(playerId, owned, active, now);
        if (gather != null) return gather;

        long foodReserve = Math.max(200, ticks.foodPerHour(playerId));
        if (amount(wallet, "food") < foodReserve) {
            for (String item : List.of("supplyPack", "resBox", "resourcePack500w")) {
                if (items.findByPlayerIdAndItemKey(playerId, item).map(row -> Objects.requireNonNullElse(row.getCount(), 0) > 0).orElse(false)) {
                    return BotAction.simple("USE_SUPPLY", item, "粮食不足，使用已有资源补给包");
                }
            }
            if (levels.getOrDefault("exchange", 0) > 0) {
                for (String source : List.of("steel", "oil", "rare")) {
                    int exchange = (int) Math.min(2000, Math.max(0, amount(wallet, source) - 500) / 4);
                    if (exchange >= 100) return new BotAction("EXCHANGE", source, null, Map.of(), exchange, "粮食不足，消耗部分富余物资补充粮食");
                }
            }
            BotAction farm = upgrade(playerId, slot, "farm", levels, wallet, 0);
            if (farm != null) return farm;
        }

        BotAction outdoor = null;
        if (active.size() < Math.min(profile.settings().maxMarches(), marchService.getMaxMarches(playerId, slot))) {
            outdoor = outdoor(profile, player, city, inventory, owned, active, now);
        }
        if (outdoor != null && profile.actionCount() % 3 == 0) return outdoor;
        if (profile.actionCount() % 3 == 1) {
            BotAction science = research(playerId, levels, wallet, foodReserve);
            if (science != null) return science;
            BotAction recruit = recruit(profile, inventory, wallet, foodReserve);
            if (recruit != null) return recruit;
        }

        List<String> order = new ArrayList<>(List.of("farm", "refinery", "oilfield", "raremine", "house", "factory", "depot", "lab", "radar", "transit", "academy", "exchange", "staff", "headquarters", "wall", "command"));
        int command = levels.getOrDefault("command", 0);
        order.sort(Comparator.comparingInt(type -> levels.getOrDefault(type, 0)));
        for (String type : order) {
            if (!"command".equals(type) && levels.getOrDefault(type, 0) >= Math.max(1, command)) continue;
            BotAction upgrade = upgrade(playerId, slot, type, levels, wallet, foodReserve);
            if (upgrade != null) return upgrade;
        }
        BotAction science = research(playerId, levels, wallet, foodReserve);
        if (science != null) return science;
        BotAction recruit = recruit(profile, inventory, wallet, foodReserve);
        if (recruit != null) return recruit;
        if (outdoor != null) return outdoor;
        return BotAction.simple("WAIT", "", "等待资源、队列、兵力或有效侦查情报；未发现适合的行动");
    }

    private BotAction gathering(long playerId, List<WildTile> owned, List<March> active, long now) {
        for (March march : active) {
            if (Boolean.TRUE.equals(march.getGathering()) && march.getGatherEndAt() != null && march.getGatherEndAt() <= now) {
                return BotAction.target("STOP_GATHER", "march", march.getId(), Map.of(), "采集已满，先收获再单独下达回城命令");
            }
            if (Boolean.TRUE.equals(march.getGatherStopped()) && !Boolean.TRUE.equals(march.getReturning())) {
                return BotAction.target("RECALL_MARCH", "march", march.getId(), Map.of(), "把已收获的部队和物资运回城市");
            }
        }
        for (WildTile tile : owned) {
            if (Boolean.TRUE.equals(tile.getGathering())) {
                if (tile.getGatherEndAt() != null && tile.getGatherEndAt() <= now) {
                    return BotAction.target("HARVEST", "wild", tile.getId(), Map.of(), "驻军采集已满，收获资源");
                }
                continue;
            }
            Map<String, Integer> garrison = JsonUtil.parseIntMap(tile.getGarrison());
            boolean depleted = Objects.requireNonNullElse(tile.getTotalRes(), 0) <= Objects.requireNonNullElse(tile.getMined(), 0);
            if (garrison.values().stream().noneMatch(count -> count > 0)) {
                if (depleted) return BotAction.target("ABANDON_WILD", "wild", tile.getId(), Map.of(), "耗尽野地已无驻军，释放占领名额");
                continue;
            }
            if (tile.getGatherHarvested() != null || depleted) {
                return BotAction.target("RECALL_WILD", "wild", tile.getId(), Map.of(), "驻军携带资源返城入库");
            }
            WildTypeDef definition = WildTypeDef.WILD_TYPES.get(tile.getType());
            if (definition != null && definition.res() != null) {
                return BotAction.target("GATHER", "wild", tile.getId(), Map.of(), "派驻部队开始采集");
            }
        }
        return null;
    }

    private BotAction upgrade(long playerId, int slot, String type, Map<String, Integer> levels, Resources wallet, long reserve) {
        List<Construction> jobs = constructions.findByPlayerIdAndCitySlot(playerId, slot);
        if (jobs.size() >= 3 || jobs.stream().anyMatch(job -> type.equals(job.getBuildingType()))) return null;
        int level = levels.getOrDefault(type, 0);
        int max = "command".equals(type) ? 10 : Math.min(10, Math.max(1, build.maxBuildingLevel(playerId)));
        int buildingSlot = 0;
        if (level >= max && "farm".equals(type) && ticks.produceOf(playerId, "farm") < ticks.foodPerHour(playerId)) {
            var farms = buildings.findByPlayerIdAndCitySlotAndType(playerId, slot, "farm");
            var unfinished = farms.stream().filter(row -> Objects.requireNonNullElse(row.getLevel(), 0) < max).findFirst();
            if (unfinished.isPresent()) { buildingSlot = unfinished.get().getSlot(); level = Objects.requireNonNullElse(unfinished.get().getLevel(), 0); }
            else { buildingSlot = farms.stream().mapToInt(row -> Objects.requireNonNullElse(row.getSlot(), 0)).max().orElse(-1) + 1; level = 0; }
            if (buildingSlot >= GameConstants.GROUP_SLOTS_RES_MAX) return null;
        }
        if (level >= max || !prerequisites.unmet(playerId, slot, "buildings", type, level + 1).isEmpty()) return null;
        if (!affordable(wallet, build.calcBuildCost(type, level), reserve)) return null;
        return new BotAction("BUILD", type, null, Map.of(), buildingSlot, "升级" + GameData.BUILDINGS.get(type).name() + "至 " + (level + 1) + " 级");
    }

    private BotAction research(long playerId, Map<String, Integer> levels, Resources wallet, long reserve) {
        if (!research.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).isEmpty()) return null;
        List<String> order = new ArrayList<>(List.of("recon_level", "prod_food", "prod_steel", "log_food", "arm_engine", "attack_tech", "defense_tech", "weapon_range", "log_load"));
        order.sort(Comparator.comparingInt(type -> tech.getTechLevel(playerId, type)));
        for (String type : order) {
            TechDef definition = GameData.TECHS.get(type);
            if (definition == null || levels.getOrDefault("lab", 0) == 0) continue;
            int level = tech.getTechLevel(playerId, type);
            if (level >= definition.maxLevel() || !prerequisites.unmet(playerId, cityScope.slot(playerId), "technologies", type, level + 1).isEmpty()) continue;
            if (affordable(wallet, tech.calcTechCost(type, level), reserve)) return BotAction.simple("RESEARCH", type, "研发" + definition.name());
        }
        return null;
    }

    /** 开荒先维持基础兵力并解锁侦查，避免持续补兵吞掉科研和军工厂升级所需钢铁。 */
    private BotAction recruit(BotStore.Profile profile, Map<String, Integer> inventory, Resources wallet, long reserve) {
        long playerId = profile.playerId();
        int slot = profile.settings().citySlot();
        if (amount(wallet, "food") < Math.max(reserve, ticks.foodPerHour(playerId) * 2L)) return null;
        var queued = production.findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(playerId, slot);
        for (String type : List.of("recon_car", "infantry", "truck", "armored")) {
            int target = switch (type) {
                case "recon_car" -> 3;
                case "truck" -> 5;
                case "armored" -> 20;
                default -> inventory.getOrDefault("recon_car", 0) >= 3 ? profile.settings().armyTarget() : Math.min(50, profile.settings().armyTarget());
            };
            int pending = queued.stream().filter(row -> type.equals(row.getUnitType())).mapToInt(ArmyProductionQueue::getUnitCount).sum();
            int count = Math.min(10, target - inventory.getOrDefault(type, 0) - pending);
            if (count <= 0 || queued.stream().anyMatch(row -> type.equals(row.getUnitType())) || !unlocks.evaluate(playerId, slot, type, false).eligible()) continue;
            UnitDef unit = GameData.UNITS.get(type);
            int civilians = Objects.requireNonNullElse(cityScope.economy(playerId).getCivilianPopulation(), 0);
            count = Math.min(count, Math.max(0, civilians - 100) / Math.max(1, unit.pop()));
            while (count > 0) {
                Map<String, Integer> cost = new HashMap<>();
                for (var entry : unit.cost().entrySet()) cost.put(entry.getKey(), entry.getValue() * count);
                if (affordable(wallet, cost, reserve)) return new BotAction("RECRUIT", type, null, Map.of(), count, "补充" + unit.name() + "，保留粮食与平民");
                count--;
            }
        }
        return null;
    }

    private BotAction outdoor(BotStore.Profile profile, Player player, PlayerCity city, Map<String, Integer> inventory,
                              List<WildTile> owned, List<March> active, long now) {
        int radius = Math.max(1, world.calcScanRadius(player.getId()));
        Map<String, Object> visible = worldView.getWorld(player, city.getX(), city.getY(), radius);
        Map<String, Integer> committed = new HashMap<>();
        active.forEach(march -> JsonUtil.parseIntMap(march.getArmy()).forEach((unit, count) -> committed.merge(unit, count, Integer::sum)));
        owned.forEach(tile -> JsonUtil.parseIntMap(tile.getGarrison()).forEach((unit, count) -> committed.merge(unit, count, Integer::sum)));
        Map<String, Integer> expedition = BotPolicy.expedition(inventory, committed, profile.settings().reservePercent(), 500);
        for (WildTile tile : owned) {
            if (Objects.requireNonNullElse(tile.getTotalRes(), 0) <= Objects.requireNonNullElse(tile.getMined(), 0) || Boolean.TRUE.equals(tile.getGathering())
                    || JsonUtil.parseIntMap(tile.getGarrison()).values().stream().anyMatch(count -> count > 0)
                    || ("wild:" + tile.getId()).equals(profile.lastTarget()) && profile.targetCooldownUntil() > now
                    || active.stream().anyMatch(march -> "wild".equals(march.getTargetKind()) && tile.getId().toString().equals(march.getTargetId()))) continue;
            WildTypeDef definition = WildTypeDef.WILD_TYPES.get(tile.getType());
            if (definition != null && definition.res() != null && !expedition.isEmpty()) {
                return BotAction.target("STATION", "wild", tile.getId(), expedition, "向已占领的资源野地补派驻军继续采集");
            }
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map<String, Object> tile : rows(visible.get("wildTiles"))) {
            if (Boolean.TRUE.equals(tile.get("occupied")) || tile.containsKey("occupiedByName")) continue;
            WildTypeDef definition = WildTypeDef.WILD_TYPES.get(Objects.toString(tile.get("type"), ""));
            if (definition == null || definition.res() == null || !(tile.get("totalRes") instanceof Number total) || total.longValue() <= 0) continue;
            if (tile.get("mined") instanceof Number mined && mined.longValue() >= total.longValue()) continue;
            if (owned.size() >= occupation.limit(player.getId(), profile.settings().citySlot()).cap()) continue;
            targets.add(withKind(tile, "wild"));
        }
        for (Map<String, Object> bandit : rows(visible.get("bandits"))) {
            if (!Boolean.TRUE.equals(bandit.get("defeated")) && !Boolean.TRUE.equals(bandit.get("sea"))) targets.add(withKind(bandit, "bandit"));
        }
        if (profile.settings().pvpEnabled()) {
            for (Map<String, Object> target : rows(visible.get("playerCities"))) {
                if (Boolean.TRUE.equals(target.get("selfCity")) || Boolean.TRUE.equals(target.get("guildmate"))
                        || target.get("shieldUntil") instanceof Number shield && shield.longValue() > now) continue;
                Object owner = target.get("ownerId");
                if (owner instanceof Number ownerId && players.findById(ownerId.longValue()).map(account -> Boolean.TRUE.equals(account.getIsBot())).orElse(false)) targets.add(withKind(target, "player"));
            }
        }
        targets.sort(Comparator.comparingDouble(target -> distance(city, target)));
        for (Map<String, Object> target : targets.stream().limit(12).toList()) {
            long targetId = ((Number) target.get("id")).longValue();
            String kind = target.get("kind").toString();
            if (active.stream().anyMatch(march -> kind.equals(march.getTargetKind()) && Long.toString(targetId).equals(march.getTargetId()))) continue;
            if ((kind + ":" + targetId).equals(profile.lastTarget()) && profile.targetCooldownUntil() > now) continue;
            int x = ((Number) target.get("x")).intValue(), y = ((Number) target.get("y")).intValue();
            var evidence = reports.findByPlayerIdAndTypeAndTargetXAndTargetYAndDetailExpiresAtGreaterThanOrderByCreatedAtDesc(player.getId(), "scout", x, y, System.currentTimeMillis());
            Map<String, Object> report = evidence.stream().filter(row -> row.getCreatedAt() != null && row.getCreatedAt() >= now - 600_000)
                    .map(row -> JsonUtil.parseObjMap(row.getData())).filter(data -> kind.equals(data.get("targetKind")))
                    .filter(data -> !"player".equals(kind) || sameNumber(data.get("targetPlayerId"), target.get("ownerId")))
                    .findFirst().orElse(Map.of());
            Map<String, Integer> defenders = intMap(report.get("army"));
            if (!(report.get("army") instanceof Map<?, ?>)) {
                if (inventory.getOrDefault("recon_car", 0) > 1 || inventory.getOrDefault("scout", 0) > 1) {
                    String recon = inventory.getOrDefault("recon_car", 0) > 1 ? "recon_car" : "scout";
                    return BotAction.target("SCOUT", kind, targetId, Map.of(recon, 1), "先侦查附近目标，未知守军不直接进攻");
                }
                continue;
            }
            if (!BotPolicy.favorable(expedition, defenders)) continue;
            if ("player".equals(kind)) {
                if (!report.containsKey("forts") || !report.containsKey("wallLevel") || ((Number) report.get("wallLevel")).intValue() > 0
                        || intMap(report.get("forts")).values().stream().anyMatch(count -> count > 0)) continue;
                Object phase = world.getWarStatus(player.getId(), targetId).get("phase");
                if ("countdown".equals(phase)) continue;
                if (!"combat".equals(phase)) return BotAction.target("DECLARE_WAR", kind, targetId, Map.of(), "侦查后向可战胜的 Bot 城市宣战，等待备战期");
            }
            return BotAction.target("ATTACK", kind, targetId, expedition, "侦查情报有效、估算兵力优势至少三倍，保留守城部队");
        }
        return null;
    }

    static long amount(Resources wallet, String resource) {
        Long value = switch (resource) { case "food" -> wallet.getFood(); case "steel" -> wallet.getSteel(); case "oil" -> wallet.getOil(); case "rare" -> wallet.getRare(); case "gold" -> wallet.getGold(); default -> 0L; };
        return Objects.requireNonNullElse(value, 0L);
    }

    private boolean affordable(Resources wallet, Map<String, Integer> cost, long reserve) {
        return cost.entrySet().stream().allMatch(entry -> amount(wallet, entry.getKey()) - entry.getValue() >= ("food".equals(entry.getKey()) ? reserve : 0));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value) { return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(); }
    private Map<String, Object> withKind(Map<String, Object> target, String kind) {
        Map<String, Object> copy = new LinkedHashMap<>(target); copy.put("kind", kind); return copy;
    }
    private double distance(PlayerCity city, Map<String, Object> target) {
        return Math.hypot(((Number) target.get("x")).doubleValue() - city.getX(), ((Number) target.get("y")).doubleValue() - city.getY());
    }
    private Map<String, Integer> intMap(Object value) { return JsonUtil.parseIntMap(JsonUtil.toJson(value)); }
    private boolean sameNumber(Object left, Object right) { return left instanceof Number first && right instanceof Number second && first.longValue() == second.longValue(); }
}
