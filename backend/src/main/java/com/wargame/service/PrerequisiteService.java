package com.wargame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.TechDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.entity.Building;
import com.wargame.model.entity.Construction;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.TechResearchQueue;
import com.wargame.repository.BuildingRepository;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.ConstructionRepository;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.repository.TechResearchQueueRepository;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Evaluates the versioned building and research requirements shared by every city. */
@Service
public class PrerequisiteService {
    @org.springframework.beans.factory.annotation.Autowired private UnitUnlockService unitUnlocks;
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode rules;
    private final BuildingRepository buildings;
    private final ConstructionRepository constructions;
    private final TechResearchQueueRepository researchQueues;
    private final PlayerCityRepository cities;
    private final WorldTerrainService terrain;
    private final ArmyProductionQueueRepository productionQueues;

    public PrerequisiteService(BuildingRepository buildings, ConstructionRepository constructions,
                               TechResearchQueueRepository researchQueues, PlayerCityRepository cities,
                               WorldTerrainService terrain, ArmyProductionQueueRepository productionQueues) {
        this.buildings = buildings;
        this.constructions = constructions;
        this.researchQueues = researchQueues;
        this.cities = cities;
        this.terrain = terrain;
        this.productionQueues = productionQueues;
        try (InputStream stream = getClass().getResourceAsStream("/game/tech-building-prerequisites.json")) {
            if (stream == null) throw new IllegalStateException("Missing prerequisite rules");
            rules = mapper.readTree(stream);
            if (rules.path("buildings").size() != BuildingDef.BUILDINGS.size()
                    || rules.path("technologies").size() != TechDef.TECHS.size()) {
                throw new IllegalStateException("Prerequisite rules do not cover the game catalog");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load prerequisite rules", e);
        }
    }

    public JsonNode rules() { return rules; }

    public String version() { return rules.path("version").asText(); }

    /** Snapshot the exact direct requirements paid for by a new queue. */
    public String snapshot(String kind, String key, int targetLevel) {
        return levelRule(kind, key, targetLevel).path("requires").toString();
    }

    public int coastalPortLevel(Long playerId) {
        if (buildings.findByPlayerIdAndType(playerId, "port").isEmpty()) return 0;
        return highestCoastalLevel(playerId, "port");
    }

    /** 征召只认本城已完成且未被拆除施工预占的建筑，其他城市不能代替制造或驻泊能力。 */
    public List<Map<String, Object>> unmetRecruitment(Long playerId, int citySlot, String unitType) {
        return unitUnlocks.evaluate(playerId, citySlot, unitType, true).missing();
    }

    /** Returns every direct unmet requirement; levels of duplicate buildings never add together. */
    public List<Map<String, Object>> unmet(Long playerId, int citySlot, String kind, String key, int targetLevel) {
        JsonNode level = levelRule(kind, key, targetLevel);
        List<Map<String, Object>> missing = new ArrayList<>();
        for (JsonNode requirement : level.path("requires")) {
            String building = requirement.path("building").asText();
            int required = requirement.path("level").asInt();
            String scope = requirement.path("scope").asText("city");
            int actual = "owned_coastal_city".equals(scope)
                    ? highestCoastalLevel(playerId, building)
                    : highestAvailableLevel(playerId, citySlot, building, null, null);
            if (actual < required) {
                missing.add(Map.of("building", building, "name", BuildingDef.BUILDINGS.get(building).name(),
                        "required", required, "current", actual, "scope", scope));
            }
        }
        return missing;
    }

    /** 每种依赖只读一次当前有效等级；等级卡片和执行校验使用相同的可用建筑口径。 */
    public Map<String, Object> evaluated(Long playerId, int citySlot) {
        Map<String, Integer> actuals = new java.util.HashMap<>();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String kind : List.of("buildings", "technologies")) {
            Map<String, Object> definitions = new java.util.LinkedHashMap<>();
            rules.path(kind).fields().forEachRemaining(entry -> {
                List<Map<String, Object>> levels = new ArrayList<>();
                for (JsonNode level : entry.getValue().path("levels")) {
                    List<Map<String, Object>> requirements = new ArrayList<>();
                    for (JsonNode r : level.path("requires")) {
                        String building = r.path("building").asText(), scope = r.path("scope").asText("city");
                        int actual = actuals.computeIfAbsent(scope + ":" + building, ignored ->
                                "owned_coastal_city".equals(scope) ? highestCoastalLevel(playerId, building)
                                        : highestAvailableLevel(playerId, citySlot, building, null, null));
                        requirements.add(Map.of("id", building, "building", building,
                                "name", BuildingDef.BUILDINGS.get(building).name(), "scope", scope,
                                "required", r.path("level").asInt(), "actual", actual, "current", actual,
                                "met", actual >= r.path("level").asInt()));
                    }
                    levels.add(Map.of("requirements", requirements, "missing", requirements.stream()
                            .filter(r -> !Boolean.TRUE.equals(r.get("met"))).toList()));
                }
                definitions.put(entry.getKey(), levels);
            });
            result.put(kind, definitions);
        }
        return result;
    }

    public String message(List<Map<String, Object>> missing) {
        return UnitUnlockService.message(missing);
    }

    /** Prevents a downgrade from invalidating work already paid for and in progress. */
    public String affectedTask(Long playerId, int citySlot, String type, Integer slot, int nextLevel) {
        int affectedSlot = slot == null ? 0 : slot;
        // 已付费舰船队列也依赖军港与造船厂；旧版记录的 buildingType=port 同样受保护。
        if (nextLevel < 1 && ("port".equals(type) || "shipyard".equals(type))) {
            for (var job : productionQueues.findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(playerId, citySlot)) {
                UnitDef unit = UnitDef.UNITS.get(job.getUnitType());
                if (unit != null && "sea".equals(unit.branch())) return "生产中的" + unit.name();
            }
        }
        for (Construction job : constructions.findByPlayerIdAndCitySlot(playerId, citySlot)) {
            if (job.getTargetLevel() == null || job.getTargetLevel() <= 0) continue;
            if (job.getPrerequisiteRequirements() == null) continue;
            if (type.equals(job.getBuildingType()) && (slot == null || slot.equals(job.getSlot()))) continue;
            if (dependsOnReducedLevel(playerId, citySlot, citySlot, type, affectedSlot, nextLevel,
                    job.getPrerequisiteRequirements())) {
                return "施工中的" + BuildingDef.BUILDINGS.get(job.getBuildingType()).name();
            }
        }
        for (TechResearchQueue job : researchQueues.findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(playerId, citySlot)) {
            if (job.getPrerequisiteRequirements() == null) continue;
            if (dependsOnReducedLevel(playerId, citySlot, citySlot, type, affectedSlot, nextLevel,
                    job.getPrerequisiteRequirements())) {
                return "研发中的" + TechDef.TECHS.get(job.getTechType()).name();
            }
        }
        // A port in another city can be the prerequisite of a laboratory construction.
        if ("port".equals(type)) {
            for (Construction job : constructions.findByPlayerId(playerId)) {
                int jobCity = Objects.requireNonNullElse(job.getCitySlot(), 0);
                if (jobCity == citySlot) continue;
                if (job.getTargetLevel() == null || job.getTargetLevel() <= 0) continue;
                if (job.getPrerequisiteRequirements() == null) continue;
                if (dependsOnReducedLevel(playerId, jobCity, citySlot, type, affectedSlot, nextLevel,
                        job.getPrerequisiteRequirements())) {
                    return "施工中的" + BuildingDef.BUILDINGS.get(job.getBuildingType()).name();
                }
            }
        }
        return null;
    }

    private boolean dependsOnReducedLevel(Long playerId, int jobCity, int reducedCity, String reducedType, Integer reducedSlot,
                                          int nextLevel, String requirementSnapshot) {
        JsonNode requirements;
        try {
            requirements = mapper.readTree(requirementSnapshot);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid queue prerequisite snapshot", exception);
        }
        for (JsonNode requirement : requirements) {
            if (!reducedType.equals(requirement.path("building").asText())) continue;
            int required = requirement.path("level").asInt();
            int remaining = "owned_coastal_city".equals(requirement.path("scope").asText())
                    ? highestCoastalAfterReduction(playerId, reducedType, reducedCity, reducedSlot, nextLevel)
                    : highestAvailableLevel(playerId, jobCity, reducedType, reducedSlot, nextLevel);
            if (remaining < required) return true;
        }
        return false;
    }

    private JsonNode levelRule(String kind, String key, int targetLevel) {
        JsonNode levels = rules.path(kind).path(key).path("levels");
        if (targetLevel < 1 || targetLevel > levels.size()) {
            throw new IllegalArgumentException("Invalid prerequisite target: " + kind + "/" + key + "/" + targetLevel);
        }
        return levels.get(targetLevel - 1);
    }

    private int highestAvailableLevel(Long playerId, int citySlot, String type, Integer reducedSlot, Integer reducedLevel) {
        int highest = 0;
        List<Construction> jobs = constructions.findByPlayerIdAndCitySlot(playerId, citySlot);
        for (Building building : buildings.findByPlayerIdAndCitySlotAndType(playerId, citySlot, type)) {
            int level = building.getLevel() == null ? 0 : building.getLevel();
            if (reducedLevel != null && reducedSlot != null && reducedSlot.equals(building.getSlot())) {
                level = Math.min(level, reducedLevel);
            }
            for (Construction job : jobs) {
                if (!type.equals(job.getBuildingType())
                        || !Objects.equals(Objects.requireNonNullElse(job.getSlot(), 0), building.getSlot())) continue;
                // Pending upgrades do not count; pending downgrades cannot support new work.
                if (job.getTargetLevel() != null && job.getTargetLevel() < level) level = job.getTargetLevel();
            }
            highest = Math.max(highest, level);
        }
        return highest;
    }

    private int highestCoastalLevel(Long playerId, String type) {
        return highestCoastalAfterReduction(playerId, type, null, null, null);
    }

    private int highestCoastalAfterReduction(Long playerId, String type, Integer reducedCity,
                                             Integer reducedSlot, Integer reducedLevel) {
        if (buildings.findByPlayerIdAndType(playerId, type).isEmpty()) return 0;
        terrain.ensure();
        int highest = 0;
        for (PlayerCity city : cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId)) {
            if (!terrain.canUsePort(city)) continue;
            // The research city's request context may differ from the coastal city being dismantled.
            Integer slot = Objects.equals(city.getCitySlot(), reducedCity) ? reducedSlot : null;
            Integer level = slot != null ? reducedLevel : null;
            highest = Math.max(highest, highestAvailableLevel(playerId, city.getCitySlot(), type, slot, level));
        }
        return highest;
    }
}
