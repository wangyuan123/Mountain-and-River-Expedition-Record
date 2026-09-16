package com.wargame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.constants.TechDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ImportService {

    private final PlayerRepository playerRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final WoundedUnitRepository woundedUnitRepository;
    private final FortificationRepository fortificationRepository;
    private final TechnologyRepository technologyRepository;
    private final OfficerRepository officerRepository;
    private final ConstructionRepository constructionRepository;
    private final AcademyRepository academyRepository;
    private final WorldMapRepository worldMapRepository;
    private final CityStateRepository cityStateRepository;
    private final NpcCityRepository npcCityRepository;
    private final PlayerCityRepository playerCityRepository;
    private final BanditRepository banditRepository;
    private final WildTileRepository wildTileRepository;
    private final MarchRepository marchRepository;
    private final IncomingMarchRepository incomingMarchRepository;
    private final ScoutReportRepository scoutReportRepository;

    private static final Set<String> MULTI_SLOT = Set.of(
            "house", "farm", "refinery", "oilfield", "raremine", "factory", "depot"
    );

    public ImportService(PlayerRepository playerRepository,
                         ResourcesRepository resourcesRepository,
                         BuildingRepository buildingRepository,
                         ArmyUnitRepository armyUnitRepository, WoundedUnitRepository woundedUnitRepository,
                         FortificationRepository fortificationRepository,
                         TechnologyRepository technologyRepository,
                         OfficerRepository officerRepository,
                         ConstructionRepository constructionRepository,
                         AcademyRepository academyRepository,
                         WorldMapRepository worldMapRepository,
                         CityStateRepository cityStateRepository,
                         NpcCityRepository npcCityRepository,
                         PlayerCityRepository playerCityRepository,
                         BanditRepository banditRepository,
                         WildTileRepository wildTileRepository,
                         MarchRepository marchRepository,
                         IncomingMarchRepository incomingMarchRepository,
                         ScoutReportRepository scoutReportRepository) {
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.buildingRepository = buildingRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.woundedUnitRepository = woundedUnitRepository;
        this.fortificationRepository = fortificationRepository;
        this.technologyRepository = technologyRepository;
        this.officerRepository = officerRepository;
        this.constructionRepository = constructionRepository;
        this.academyRepository = academyRepository;
        this.worldMapRepository = worldMapRepository;
        this.cityStateRepository = cityStateRepository;
        this.npcCityRepository = npcCityRepository;
        this.playerCityRepository = playerCityRepository;
        this.banditRepository = banditRepository;
        this.wildTileRepository = wildTileRepository;
        this.marchRepository = marchRepository;
        this.incomingMarchRepository = incomingMarchRepository;
        this.scoutReportRepository = scoutReportRepository;
    }

    // ================================================================
    // importFromJson
    // ================================================================

    @Transactional
    public void importFromJson(Long playerId, String jsonStr) {
        if (playerId == null) {
            throw new IllegalArgumentException("Invalid player ID: null");
        }
        if (jsonStr == null || jsonStr.isBlank()) {
            throw new IllegalArgumentException("JSON save data is empty");
        }

        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));

        JsonNode root = JsonUtil.parseTree(jsonStr);
        if (root == null || root.isNull()) {
            throw new IllegalArgumentException("Invalid JSON save data");
        }

        if (playerCityRepository.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId).size() > 1) {
            throw new IllegalArgumentException("旧版单城存档不能覆盖多城账号");
        }
        // Clear existing data for this player
        clearPlayerData(playerId);

        // --- Import player info ---
        importPlayerInfo(player, root);
        playerRepository.save(player);

        // --- Import resources ---
        importResources(playerId, root);

        // --- Import buildings (with migration) ---
        importBuildings(playerId, root);

        // --- Import army ---
        importArmy(playerId, root);

        // --- Import forts ---
        importForts(playerId, root);

        // --- Import tech ---
        importTech(playerId, root);

        // --- Import officers ---
        importOfficers(playerId, root);

        // --- Import constructions (with migration) ---
        importConstructions(playerId, root);

        // --- Import academy ---
        importAcademy(playerId, root);

        // --- Import cityState ---
        importCityState(playerId, root);

        // --- Import world ---
        importWorld(player, root);

        // --- Import marches ---
        importMarches(playerId, root);

        // --- Import incoming ---
        importIncoming(playerId, root);

        // --- Import reports ---
        importReports(playerId, root);
    }

    // ================================================================
    // Private import methods
    // ================================================================

    private void clearPlayerData(Long playerId) {
        resourcesRepository.findByPlayerId(playerId).ifPresent(resourcesRepository::delete);
        buildingRepository.deleteByPlayerId(playerId);
        armyUnitRepository.deleteByPlayerId(playerId);
        woundedUnitRepository.deleteByPlayerId(playerId);
        fortificationRepository.deleteByPlayerId(playerId);
        technologyRepository.deleteByPlayerId(playerId);
        officerRepository.deleteByPlayerId(playerId);
        constructionRepository.deleteByPlayerId(playerId);
        academyRepository.findByPlayerId(playerId).ifPresent(academyRepository::delete);
        cityStateRepository.findByPlayerId(playerId).ifPresent(cityStateRepository::delete);
        marchRepository.findByPlayerId(playerId).forEach(marchRepository::delete);
        incomingMarchRepository.findByTargetPlayerId(playerId).forEach(incomingMarchRepository::delete);
        scoutReportRepository.findByPlayerId(playerId).forEach(scoutReportRepository::delete);
    }

    private void importPlayerInfo(Player player, JsonNode root) {
        JsonNode playerNode = root.path("player");
        if (!playerNode.isMissingNode() && playerNode.isObject()) {
            if (playerNode.has("faction")) {
                player.setFaction(playerNode.path("faction").asText("allies"));
            }
            if (playerNode.has("cityName")) {
                player.setCityName(playerNode.path("cityName").asText(""));
            }
            if (playerNode.has("name")) {
                // JS player.name is the commander name, stored in username on backend
                // Only update if player username wasn't set
            }
        }

        // Position
        JsonNode worldNode = root.path("world");
        if (!worldNode.isMissingNode() && worldNode.isObject()) {
            JsonNode posNode = worldNode.path("pos");
            if (posNode.isObject()) {
                player.setPosX(posNode.path("x").asInt(WorldConfig.SIZE / 2));
                player.setPosY(posNode.path("y").asInt(WorldConfig.SIZE / 2));
            }
            JsonNode cityPosNode = worldNode.path("cityPos");
            if (cityPosNode.isObject()) {
                player.setCityPosX(cityPosNode.path("x").asInt(player.getPosX()));
                player.setCityPosY(cityPosNode.path("y").asInt(player.getPosY()));
            } else if (posNode.isObject()) {
                // Migration: if no cityPos, set cityPos = pos
                player.setCityPosX(player.getPosX());
                player.setCityPosY(player.getPosY());
            }
        }

        // Scalar fields with defaults
        player.setTax(root.path("tax").isNumber() ? root.path("tax").asInt() : 30);
        player.setMorale(root.path("morale").isNumber() ? root.path("morale").asInt() : 70);
        player.setPrestige(Math.max(0, root.path("prestige").asInt(0)));
        player.setLastTick(root.path("lastTick").asLong(System.currentTimeMillis()));
    }

    private void importResources(Long playerId, JsonNode root) {
        JsonNode resNode = root.path("resources");
        Resources resources = new Resources();
        resources.setPlayerId(playerId);
        resources.setFood(resNode.path("food").asInt(0));
        resources.setSteel(resNode.path("steel").asInt(0));
        resources.setOil(resNode.path("oil").asInt(0));
        resources.setRare(resNode.path("rare").asInt(0));
        resources.setGold(resNode.path("gold").asInt(0));
        resourcesRepository.save(resources);
    }

    private void importBuildings(Long playerId, JsonNode root) {
        JsonNode bNode = root.path("buildings");
        if (bNode.isMissingNode() || !bNode.isObject()) return;

        // Migration: delete barracks (migrateBuildings removes it)
        // For MULTI_SLOT buildings: arrays -> each element becomes a row; single number -> one row
        // For non-MULTI_SLOT: single number -> one row
        Iterator<Map.Entry<String, JsonNode>> fields = bNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String type = entry.getKey();
            JsonNode val = entry.getValue();

            // Skip barracks (migration removes it)
            if ("barracks".equals(type)) continue;

            if (MULTI_SLOT.contains(type)) {
                // Multi-slot: array of levels, or single number converted to [number], or missing -> []
                if (val.isArray()) {
                    for (int idx = 0; idx < val.size(); idx++) {
                        int lv = val.get(idx).asInt(0);
                        if (lv > 0) {
                            // ⚠️ idx 即槽位号 —— 必须显式传, 否则 V5 UNIQUE 冲突
                            saveBuilding(playerId, type, lv, idx);
                        }
                    }
                } else if (val.isNumber()) {
                    int lv = val.asInt(0);
                    if (lv > 0) {
                        saveBuilding(playerId, type, lv, 0);
                    }
                }
            } else {
                // Single-slot: just the level number
                if (val.isNumber()) {
                    int lv = val.asInt(0);
                    saveBuilding(playerId, type, lv, 0);
                } else if (val.isArray() && val.size() > 0) {
                    // Some single-slot might have been stored as array erroneously
                    int lv = val.get(0).asInt(0);
                    saveBuilding(playerId, type, lv, 0);
                }
            }
        }
    }

    private void importArmy(Long playerId, JsonNode root) {
        JsonNode armyNode = root.path("army");
        if (armyNode.isMissingNode() || !armyNode.isObject()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = armyNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            int count = entry.getValue().asInt(0);
            if (count > 0) {
                ArmyUnit unit = new ArmyUnit();
                unit.setPlayerId(playerId);
                unit.setType(entry.getKey());
                unit.setCount(count);
                armyUnitRepository.save(unit);
            }
        }
    }

    private void importForts(Long playerId, JsonNode root) {
        JsonNode fortsNode = root.path("forts");
        if (fortsNode.isMissingNode() || !fortsNode.isObject()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = fortsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            int count = entry.getValue().asInt(0);
            if (count > 0) {
                Fortification fort = new Fortification();
                fort.setPlayerId(playerId);
                fort.setType(entry.getKey());
                fort.setCount(count);
                fortificationRepository.save(fort);
            }
        }
    }

    private void importTech(Long playerId, JsonNode root) {
        JsonNode techNode = root.path("tech");
        if (techNode.isMissingNode() || !techNode.isObject()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = techNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!TechDef.TECHS.containsKey(entry.getKey())) continue;
            int level = entry.getValue().asInt(0);
            if (level > 0) {
                Technology tech = new Technology();
                tech.setPlayerId(playerId);
                tech.setType(entry.getKey());
                tech.setLevel(level);
                technologyRepository.save(tech);
            }
        }
    }

    private void importOfficers(Long playerId, JsonNode root) {
        JsonNode officersNode = root.path("officers");
        if (officersNode.isMissingNode() || !officersNode.isArray()) return;

        for (JsonNode offNode : officersNode) {
            Officer officer = new Officer();
            officer.setPlayerId(playerId);
            officer.setName(offNode.path("name").asText("未知军官"));
            officer.setStar(offNode.path("star").asInt(1));
            officer.setLevel(offNode.path("level").asInt(1));
            officer.setMilitary(offNode.path("military").asInt(0));
            officer.setLogistics(offNode.path("logistics").asInt(0));
            officer.setKnowledge(offNode.path("knowledge").asInt(0));
            officer.setLoyalty(offNode.path("loyalty").asInt(60));
            officer.setSalary(offNode.path("salary").asInt(0));
            officer.setRole(offNode.path("role").asText("idle"));
            officer.setRecruitAt(offNode.path("recruitAt").asLong(0));
            officer.setRewardAt(offNode.path("rewardAt").asLong(0));
            officer.setAttrPoints(offNode.path("attrPoints").asInt(0));

            // Skills - serialize as JSON text
            JsonNode skillsNode = offNode.path("skills");
            if (skillsNode.isArray() && skillsNode.size() > 0) {
                officer.setSkills(JsonUtil.toJson(skillsNode));
            } else {
                officer.setSkills("[]");
            }

            // Bio
            officer.setBio(offNode.path("bio").asText(""));

            officerRepository.save(officer);
        }
    }

    private void importConstructions(Long playerId, JsonNode root) {
        JsonNode constructionsNode = root.path("constructions");

        // Migration: if constructions is not an array but construction (singular) exists, wrap it
        List<JsonNode> jobList = new ArrayList<>();
        if (constructionsNode.isArray()) {
            for (JsonNode job : constructionsNode) {
                jobList.add(job);
            }
        } else {
            JsonNode singleConstruction = root.path("construction");
            if (!singleConstruction.isMissingNode() && singleConstruction.isObject()) {
                jobList.add(singleConstruction);
            }
        }

        for (JsonNode job : jobList) {
            String buildingType = job.has("id") ? job.path("id").asText() : job.path("buildingType").asText("");
            if (buildingType.isEmpty()) continue;

            Construction construction = new Construction();
            construction.setPlayerId(playerId);
            construction.setBuildingType(buildingType);
            construction.setTargetLevel(job.path("targetLevel").asInt(
                    job.path("level").asInt(1)));
            construction.setStartAt(job.path("startedAt").asLong(job.path("startAt").asLong(0)));
            construction.setFinishAt(job.path("finishesAt").asLong(job.path("finishAt").asLong(0)));
            construction.setSlot(job.has("slot") && !job.path("slot").isNull()
                    ? job.path("slot").asInt() : null);
            constructionRepository.save(construction);
        }
    }

    private void importAcademy(Long playerId, JsonNode root) {
        JsonNode academyNode = root.path("academy");
        Academy academy = new Academy();
        academy.setPlayerId(playerId);

        if (!academyNode.isMissingNode() && academyNode.isObject()) {
            JsonNode listNode = academyNode.path("list");
            if (listNode.isArray()) {
                academy.setOfficers(JsonUtil.toJson(listNode));
            } else {
                academy.setOfficers("[]");
            }
            academy.setRefreshAt(academyNode.path("refreshAt").asLong(0));
        } else {
            academy.setOfficers("[]");
            academy.setRefreshAt(0L);
        }

        academyRepository.save(academy);
    }

    private void importCityState(Long playerId, JsonNode root) {
        JsonNode csNode = root.path("cityState");
        CityState cs = new CityState();
        cs.setPlayerId(playerId);

        if (!csNode.isMissingNode() && csNode.isObject()) {
            cs.setStatus(csNode.path("status").asText("peace"));
            // warTarget can be a string or null in JS
            JsonNode warTargetNode = csNode.path("warTarget");
            if (warTargetNode.isNumber()) {
                cs.setWarTargetId(warTargetNode.asLong());
            } else {
                cs.setWarTargetId(null);
            }
            cs.setWarEndAt(csNode.path("warUntil").asLong(0));
            cs.setShieldUntil(csNode.path("shieldUntil").asLong(0));
            cs.setPeaceUntil(csNode.path("peaceUntil").asLong(0));
            cs.setWarAt(0L);
        } else {
            cs.setStatus("peace");
            cs.setWarTargetId(null);
            cs.setWarAt(0L);
            cs.setWarEndAt(0L);
            cs.setShieldUntil(0L);
            cs.setPeaceUntil(0L);
        }

        cityStateRepository.save(cs);
    }

    private void importWorld(Player player, JsonNode root) {
        JsonNode worldNode = root.path("world");

        if (worldNode.isMissingNode() || !worldNode.isObject()) {
            // No world data - position defaults already set in importPlayerInfo
            return;
        }

        // Create or reuse a WorldMap
        WorldMap worldMap = worldMapRepository.findFirstByOrderByIdAsc().orElse(null);
        if (worldMap == null) {
            worldMap = new WorldMap();
            worldMap.setSize(WorldConfig.SIZE);
            worldMap.setScanRadius(WorldConfig.VIEW_RADIUS);
            worldMap.setPosX(player.getPosX() != null ? player.getPosX() : WorldConfig.SIZE / 2);
            worldMap.setPosY(player.getPosY() != null ? player.getPosY() : WorldConfig.SIZE / 2);
            worldMap = worldMapRepository.save(worldMap);
        }

        Long worldId = worldMap.getId();

        // Clear existing world entities
        banditRepository.findByWorldId(worldId).forEach(banditRepository::delete);
        npcCityRepository.findByWorldId(worldId).forEach(npcCityRepository::delete);
        playerCityRepository.findByWorldId(worldId).forEach(playerCityRepository::delete);
        wildTileRepository.findByWorldId(worldId).forEach(wildTileRepository::delete);

        // Import NPC cities
        JsonNode npcCitiesNode = worldNode.path("npcCities");
        if (npcCitiesNode.isArray()) {
            for (JsonNode ncNode : npcCitiesNode) {
                NpcCity npcCity = new NpcCity();
                npcCity.setWorldId(worldId);
                npcCity.setName(ncNode.path("name").asText("寇城"));
                npcCity.setLevel(ncNode.path("level").asInt(1));
                npcCity.setX(ncNode.path("x").asInt(0));
                npcCity.setY(ncNode.path("y").asInt(0));
                npcCity.setArmy(jsonNodeToJson(ncNode.path("army")));
                npcCity.setForts(jsonNodeToJson(ncNode.path("forts")));
                npcCity.setResources(jsonNodeToJson(ncNode.path("reward")));
                npcCity.setDefeated(ncNode.path("defeated").asBoolean(false));
                npcCityRepository.save(npcCity);
            }
        }

        // Import player cities
        JsonNode playerCitiesNode = worldNode.path("playerCities");
        if (playerCitiesNode.isArray()) {
            for (JsonNode pcNode : playerCitiesNode) {
                PlayerCity playerCity = new PlayerCity();
                playerCity.setWorldId(worldId);
                playerCity.setName(pcNode.path("name").asText("玩家城市"));
                playerCity.setOwnerId(null);
                playerCity.setLevel(pcNode.path("level").asInt(1));
                playerCity.setX(pcNode.path("x").asInt(0));
                playerCity.setY(pcNode.path("y").asInt(0));
                playerCity.setArmy(jsonNodeToJson(pcNode.path("army")));
                playerCity.setForts(jsonNodeToJson(pcNode.path("forts")));
                playerCity.setResources(jsonNodeToJson(pcNode.path("reward")));
                playerCity.setPrestige(pcNode.path("prestige").asInt(0));
                playerCity.setWarAt(0L);
                playerCity.setWarEndAt(pcNode.path("coolAt").asLong(0));
                playerCityRepository.save(playerCity);
            }
        }

        // Import bandits
        JsonNode banditsNode = worldNode.path("bandits");
        if (banditsNode.isArray()) {
            for (JsonNode bNode : banditsNode) {
                Bandit bandit = new Bandit();
                bandit.setWorldId(worldId);
                bandit.setName(bNode.path("name").asText("日寇据点"));
                bandit.setLevel(bNode.path("level").asInt(1));
                bandit.setX(bNode.path("x").asInt(0));
                bandit.setY(bNode.path("y").asInt(0));
                bandit.setArmy(jsonNodeToJson(bNode.path("army")));
                bandit.setDefeated(bNode.path("defeated").asBoolean(false));
                banditRepository.save(bandit);
            }
        }

        // Import wild tiles (with migration: generate if missing)
        JsonNode wildTilesNode = worldNode.path("wildTiles");
        if (wildTilesNode.isArray()) {
            for (JsonNode wtNode : wildTilesNode) {
                WildTile wildTile = new WildTile();
                wildTile.setWorldId(worldId);
                wildTile.setType(wtNode.path("type").asText("forest"));
                wildTile.setX(wtNode.path("x").asInt(0));
                wildTile.setY(wtNode.path("y").asInt(0));
                wildTile.setLevel(wtNode.path("level").asInt(1));
                wildTile.setGarrison(jsonNodeToJson(wtNode.path("garrison")));
                wildTile.setScouted(wtNode.path("scouted").asBoolean(false));
                wildTile.setOccupied(wtNode.path("occupied").asBoolean(false));
                wildTile.setOccupiedBy(null);
                wildTile.setTotalRes(wtNode.path("totalRes").asInt(0));
                wildTile.setMined(wtNode.path("mined").asInt(0));
                wildTileRepository.save(wildTile);
            }
        }
    }

    private void importMarches(Long playerId, JsonNode root) {
        JsonNode worldNode = root.path("world");
        if (worldNode.isMissingNode()) return;

        JsonNode marchesNode = worldNode.path("marches");
        if (!marchesNode.isArray()) return;

        for (JsonNode mNode : marchesNode) {
            March march = new March();
            march.setPlayerId(playerId);
            march.setTargetKind(mNode.path("targetKind").asText(null));
            march.setTargetIdx(mNode.has("targetIdx") ? mNode.path("targetIdx").asInt() : null);
            march.setTargetId(mNode.path("targetId").asText(null));
            march.setTargetName(mNode.path("targetName").asText(null));
            march.setTargetX(mNode.has("targetX") ? mNode.path("targetX").asInt() : null);
            march.setTargetY(mNode.has("targetY") ? mNode.path("targetY").asInt() : null);
            march.setFromX(mNode.has("fromX") ? mNode.path("fromX").asInt() : null);
            march.setFromY(mNode.has("fromY") ? mNode.path("fromY").asInt() : null);
            march.setDistance(mNode.path("distance").asInt(0));
            march.setAction(mNode.path("action").asText(null));
            march.setArmy(jsonNodeToJson(mNode.path("army")));

            // commanderId can be a string ID like "o_123" or a number
            JsonNode cmdNode = mNode.path("commanderId");
            if (cmdNode.isNumber()) {
                march.setCommanderId(cmdNode.asLong());
            } else {
                march.setCommanderId(null);
            }

            march.setCarryRes(jsonNodeToJson(mNode.path("carryRes")));
            march.setStartAt(mNode.path("startAt").asLong(0));
            march.setArriveAt(mNode.path("arriveAt").asLong(0));
            march.setReturning(mNode.path("returning").asBoolean(false));
            march.setGathering(mNode.path("gathering").asBoolean(false));
            march.setGatherEndAt(mNode.path("gatherEndAt").asLong(0));
            march.setGatherAmount(mNode.path("gatherAmount").asInt(0));
            march.setGatherRes(mNode.has("gatherRes") ? mNode.path("gatherRes").asText() : null);

            marchRepository.save(march);
        }
    }

    private void importIncoming(Long playerId, JsonNode root) {
        JsonNode worldNode = root.path("world");
        if (worldNode.isMissingNode()) return;

        JsonNode incomingNode = worldNode.path("incoming");
        if (!incomingNode.isArray()) return;

        for (JsonNode imNode : incomingNode) {
            IncomingMarch incoming = new IncomingMarch();
            incoming.setTargetPlayerId(playerId);
            incoming.setFromName(imNode.path("fromName").asText(null));
            incoming.setFromX(imNode.has("fromX") ? imNode.path("fromX").asInt() : null);
            incoming.setFromY(imNode.has("fromY") ? imNode.path("fromY").asInt() : null);
            incoming.setArmy(jsonNodeToJson(imNode.path("army")));
            incoming.setArriveAt(imNode.path("arriveAt").asLong(0));
            incoming.setAction(imNode.path("action").asText(null));

            incomingMarchRepository.save(incoming);
        }
    }

    private void importReports(Long playerId, JsonNode root) {
        JsonNode reportsNode = root.path("reports");
        if (!reportsNode.isArray()) return;

        for (JsonNode rNode : reportsNode) {
            ScoutReport report = new ScoutReport();
            report.setPlayerId(playerId);
            report.setTargetX(rNode.has("x") ? rNode.path("x").asInt() : null);
            report.setTargetY(rNode.has("y") ? rNode.path("y").asInt() : null);
            report.setTargetName(rNode.path("targetName").asText(null));

            // Store the entire report data as JSON
            report.setData(JsonUtil.toJson(rNode));
            report.setCreatedAt(rNode.path("time").asLong(System.currentTimeMillis()));

            scoutReportRepository.save(report);
        }
    }

    // ================================================================
    // Utility helpers
    // ================================================================

    private void saveBuilding(Long playerId, String type, int level, int slot) {
        Building b = new Building();
        b.setPlayerId(playerId);
        b.setType(type);
        b.setLevel(level);
        b.setSlot(slot);
        buildingRepository.save(b);
    }

    private String jsonNodeToJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return JsonUtil.toJson(node);
    }
}
