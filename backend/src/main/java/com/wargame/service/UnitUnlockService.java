package com.wargame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.TechDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/** 新生产、新招募及奖励发兵共用的实时资格；不限制已有部队和已付费队列。 */
@Service
public class UnitUnlockService {
    private final Map<String, JsonNode> rules = new LinkedHashMap<>();
    private final String version;
    private final BuildingRepository buildings;
    private final ConstructionRepository constructions;
    private final TechnologyRepository technologies;
    private final PlayerCityRepository cities;
    private final WorldTerrainService terrain;
    @PersistenceContext private EntityManager em;

    public record Result(String unitId, String ruleVersion, boolean eligible,
                         List<Map<String, Object>> requirements, List<Map<String, Object>> missing) {
        public String snapshot() { return JsonUtil.toJson(this); }
    }

    public UnitUnlockService(BuildingRepository buildings, ConstructionRepository constructions,
                             TechnologyRepository technologies, PlayerCityRepository cities, WorldTerrainService terrain) {
        this.buildings = buildings;
        this.constructions = constructions;
        this.technologies = technologies;
        this.cities = cities;
        this.terrain = terrain;
        try (InputStream stream = getClass().getResourceAsStream("/game/unit-unlock-prerequisites.json")) {
            JsonNode root = new ObjectMapper().readTree(Objects.requireNonNull(stream));
            version = root.path("version").asText();
            for (JsonNode rule : root.path("units")) {
                String id = rule.path("id").asText();
                UnitDef unit = UnitDef.UNITS.get(id);
                if (unit == null || rules.putIfAbsent(id, rule) != null
                        || !unit.build().equals(rule.path("productionBuilding").asText())) {
                    throw new IllegalStateException("兵种目录或生产归属不匹配: " + id);
                }
                validate(rule.path("buildings"), BuildingDef.BUILDINGS.keySet());
                validate(rule.path("technologies"), TechDef.TECHS.keySet());
                if (!rule.path("buildings").has(unit.build())
                        || ("sea".equals(unit.branch()) != rule.path("coastalOrLegacyNavalRequired").asBoolean())) {
                    throw new IllegalStateException("兵种缺少生产或地理条件: " + id);
                }
            }
            if (version.isBlank() || !rules.keySet().equals(UnitDef.UNITS.keySet())) {
                throw new IllegalStateException("兵种解锁配置未完整覆盖目录");
            }
        } catch (Exception e) { throw new IllegalStateException("无法加载兵种解锁配置", e); }
    }

    private void validate(JsonNode entries, Set<String> catalog) {
        entries.fields().forEachRemaining(e -> {
            if (!catalog.contains(e.getKey()) || !e.getValue().isInt()
                    || e.getValue().asInt() < 1 || e.getValue().asInt() > 10) {
                throw new IllegalStateException("无效兵种条件: " + e);
            }
        });
    }

    /** 玩家锁后的当前读，避免锁等待前的一致性快照或一级缓存放行过时条件。 */
    private <T> List<T> rows(Class<T> type, Long player, Integer slot, boolean current) {
        var query = em.createQuery("select r from " + type.getSimpleName()
                + " r where r.playerId = :player" + (slot == null ? "" : " and r.citySlot = :slot"), type)
                .setParameter("player", player);
        if (slot != null) query.setParameter("slot", slot);
        if (current) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        List<T> result = query.getResultList();
        if (current) result.forEach(row -> em.refresh(row, LockModeType.PESSIMISTIC_WRITE));
        return result;
    }

    /** 只取最高单栋已完成等级；升级不预支，降级及拆除立即预扣。 */
    private int available(List<Building> facilities, List<Construction> jobs, String id) {
        int highest = 0;
        for (Building b : facilities) {
            if (!id.equals(b.getType())) continue;
            int level = Objects.requireNonNullElse(b.getLevel(), 0);
            for (Construction job : jobs) {
                if (id.equals(job.getBuildingType())
                        && Objects.equals(Objects.requireNonNullElse(job.getSlot(), 0), Objects.requireNonNullElse(b.getSlot(), 0))
                        && job.getTargetLevel() != null) level = Math.min(level, job.getTargetLevel());
            }
            highest = Math.max(highest, level);
        }
        return highest;
    }

    public int availableBuildingLevel(Long player, int slot, String id) {
        return available(buildings.findByPlayerIdAndCitySlot(player, slot), constructions.findByPlayerIdAndCitySlot(player, slot), id);
    }

    /** current=true 仅供已锁定玩家且处于写事务内的新获取入口调用。科技始终取全帝国完成记录。 */
    public Result evaluate(Long player, int slot, String unit, boolean current) {
        return evaluate(unit, rows(Building.class, player, slot, current), rows(Construction.class, player, slot, current),
                rows(Technology.class, player, null, current), city(player, slot, current));
    }

    private PlayerCity city(Long player, int slot, boolean current) {
        if (!current) return cities.findByOwnerIdAndCitySlot(player, slot).orElse(null);
        var list = em.createQuery("select c from PlayerCity c where c.ownerId = :player and c.citySlot = :slot", PlayerCity.class)
                .setParameter("player", player).setParameter("slot", slot).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        list.forEach(c -> em.refresh(c, LockModeType.PESSIMISTIC_WRITE));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 一次读取城市与账号进度，返回全部兵种，客户端只展示服务端条件。 */
    public Map<String, Result> all(Long player, int slot) {
        var facilities = buildings.findByPlayerIdAndCitySlot(player, slot);
        var jobs = constructions.findByPlayerIdAndCitySlot(player, slot);
        var tech = technologies.findByPlayerId(player);
        var city = city(player, slot, false);
        Map<String, Result> result = new LinkedHashMap<>();
        rules.keySet().forEach(id -> result.put(id, evaluate(id, facilities, jobs, tech, city)));
        return result;
    }

    private Result evaluate(String unit, List<Building> facilities, List<Construction> jobs, List<Technology> tech, PlayerCity city) {
        JsonNode rule = rules.get(unit);
        if (rule == null) throw new IllegalArgumentException("未知兵种: " + unit);
        List<Map<String, Object>> requirements = new ArrayList<>();
        rule.path("buildings").fields().forEachRemaining(e -> requirements.add(requirement("building", e.getKey(),
                BuildingDef.BUILDINGS.get(e.getKey()).name(), e.getValue().asInt(), available(facilities, jobs, e.getKey()), "city")));
        rule.path("technologies").fields().forEachRemaining(e -> requirements.add(requirement("technology", e.getKey(),
                TechDef.TECHS.get(e.getKey()).name(), e.getValue().asInt(), tech.stream().filter(t -> e.getKey().equals(t.getType()))
                        .mapToInt(t -> Objects.requireNonNullElse(t.getLevel(), 0)).max().orElse(0), "empire")));
        if (rule.path("coastalOrLegacyNavalRequired").asBoolean()) {
            // 解锁查询只读取当前地形；游戏世界尚未初始化时按不满足地理条件处理，不触发地图初始化副作用。
            requirements.add(requirement("geography", "coastal_or_legacy_naval", "沿海或既有海军城市资格", 1,
                    city != null && terrain.canUsePort(city) ? 1 : 0, "city"));
        }
        var missing = requirements.stream().filter(r -> !Boolean.TRUE.equals(r.get("met"))).toList();
        return new Result(unit, version, missing.isEmpty(), List.copyOf(requirements), missing);
    }

    private Map<String, Object> requirement(String kind, String id, String name, int required, int current, String scope) {
        return Map.of("kind", kind, "id", id, "name", name, "required", required,
                "current", current, "scope", scope, "met", current >= required);
    }

    public String message(Result result) { return message(result.missing()); }

    public static String message(List<Map<String, Object>> missing) {
        if (missing.isEmpty()) return "";
        return missing.stream().map(r -> "geography".equals(r.get("kind")) ? "需" + r.get("name")
                : "需" + ("empire".equals(r.get("scope")) ? "全帝国" : "owned_coastal_city".equals(r.get("scope")) ? "任一所属沿海城市的" : "本城")
                + r.get("name") + " Lv." + r.get("required") + "（当前 Lv." + r.get("current") + "）")
                .reduce((a, b) -> a + "；" + b).orElse("");
    }
}
