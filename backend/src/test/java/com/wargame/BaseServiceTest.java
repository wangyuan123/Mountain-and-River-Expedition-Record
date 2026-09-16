package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class BaseServiceTest {

    @Autowired protected TickService tickService;
    @Autowired protected BattleService battleService;
    @Autowired protected MarchService marchService;
    @Autowired protected BuildService buildService;
    @Autowired protected GameStateService gameStateService;
    @Autowired protected WorldService worldService;

    @Autowired protected PlayerRepository playerRepository;
    @Autowired protected ResourcesRepository resourcesRepository;
    @Autowired protected BuildingRepository buildingRepository;
    @Autowired protected ArmyUnitRepository armyUnitRepository;
    @Autowired protected OfficerRepository officerRepository;
    @Autowired protected CityStateRepository cityStateRepository;
    @Autowired protected TechnologyRepository technologyRepository;
    @Autowired protected MarchRepository marchRepository;
    @Autowired protected ConstructionRepository constructionRepository;
    @Autowired protected WildTileRepository wildTileRepository;
    @Autowired protected BanditRepository banditRepository;
    @Autowired protected NpcCityRepository npcCityRepository;
    @Autowired protected PlayerCityRepository playerCityRepository;
    @Autowired protected FortificationRepository fortificationRepository;
    @Autowired protected IncomingMarchRepository incomingMarchRepository;
    @Autowired protected ScoutReportRepository scoutReportRepository;
    @Autowired protected WorldMapRepository worldMapRepository;

    // ================================================================
    // Helper: Create a test player with default resources
    // ================================================================

    protected Player createTestPlayer() {
        return createTestPlayer("testplayer", 30);
    }

    protected Player createTestPlayer(String username, int tax) {
        Player player = new Player();
        player.setUsername(username);
        player.setPasswordHash("dummy-hash");
        player.setFaction("allies");
        player.setCityName("TestCity");
        player.setPosX(10);
        player.setPosY(10);
        player.setCityPosX(10);
        player.setCityPosY(10);
        player.setTax(tax);
        player.setMorale(70);
        player.setPrestige(0);
        player.setLastTick(System.currentTimeMillis());
        player = playerRepository.save(player);

        Resources res = new Resources();
        res.setPlayerId(player.getId());
        res.setFood(1000);
        res.setSteel(1000);
        res.setOil(1000);
        res.setRare(500);
        res.setGold(500);
        resourcesRepository.save(res);

        return player;
    }

    // ================================================================
    // Helper: Set specific resource amounts for a player
    // ================================================================

    protected void giveResources(Long playerId, int food, int steel, int oil, int rare, int gold) {
        Resources res = resourcesRepository.findByPlayerId(playerId).orElse(null);
        if (res == null) {
            res = new Resources();
            res.setPlayerId(playerId);
        }
        res.setFood(food);
        res.setSteel(steel);
        res.setOil(oil);
        res.setRare(rare);
        res.setGold(gold);
        resourcesRepository.save(res);
    }

    // ================================================================
    // Helper: Create a minimal world for testing
    // ================================================================

    protected WorldMap createTestWorld() {
        WorldMap world = new WorldMap();
        world.setSize(200);
        world.setScanRadius(3);
        world.setPosX(0);
        world.setPosY(0);
        return worldMapRepository.save(world);
    }

    // ================================================================
    // Helper: Create a building for a player
    // ================================================================

    protected Building createBuilding(Long playerId, String type, int level) {
        Building building = new Building();
        building.setPlayerId(playerId);
        building.setType(type);
        building.setLevel(level);
        return buildingRepository.save(building);
    }

    // ================================================================
    // Helper: Create an army unit for a player
    // ================================================================

    protected ArmyUnit createArmyUnit(Long playerId, String type, int count) {
        ArmyUnit unit = new ArmyUnit();
        unit.setPlayerId(playerId);
        unit.setType(type);
        unit.setCount(count);
        return armyUnitRepository.save(unit);
    }

    // ================================================================
    // Helper: Create an officer for a player
    // ================================================================

    protected Officer createOfficer(Long playerId, String role, int military, int logistics, int knowledge) {
        Officer officer = new Officer();
        officer.setPlayerId(playerId);
        officer.setName("TestOfficer");
        officer.setStar(1);
        officer.setLevel(1);
        officer.setMilitary(military);
        officer.setLogistics(logistics);
        officer.setKnowledge(knowledge);
        officer.setLoyalty(80);
        officer.setSalary(0);
        officer.setRole(role);
        officer.setRecruitAt(0L);
        officer.setRewardAt(0L);
        return officerRepository.save(officer);
    }

    // ================================================================
    // Helper: Create a technology record for a player
    // ================================================================

    protected Technology createTechnology(Long playerId, String type, int level) {
        Technology tech = new Technology();
        tech.setPlayerId(playerId);
        tech.setType(type);
        tech.setLevel(level);
        return technologyRepository.save(tech);
    }

    // ================================================================
    // Helper: Create a city state for a player
    // ================================================================

    protected CityState createCityState(Long playerId, String status, long shieldUntil, long warEndAt) {
        CityState cs = new CityState();
        cs.setPlayerId(playerId);
        cs.setStatus(status);
        cs.setWarTargetId(null);
        cs.setWarAt(0L);
        cs.setWarEndAt(warEndAt);
        cs.setShieldUntil(shieldUntil);
        cs.setPeaceUntil(0L);
        return cityStateRepository.save(cs);
    }

    // ================================================================
    // Helper: Create a wild tile
    // ================================================================

    protected WildTile createWildTile(Long worldId, String type, int x, int y, int level,
                                      Map<String, Integer> garrison, int totalRes) {
        WildTile wt = new WildTile();
        wt.setWorldId(worldId);
        wt.setType(type);
        wt.setX(x);
        wt.setY(y);
        wt.setLevel(level);
        wt.setGarrison(garrison != null ? JsonUtil.toJson(garrison) : null);
        wt.setScouted(false);
        wt.setOccupied(false);
        wt.setOccupiedBy(null);
        wt.setTotalRes(totalRes);
        wt.setMined(0);
        return wildTileRepository.save(wt);
    }

    // ================================================================
    // Helper: Create a construction record
    // ================================================================

    protected Construction createConstruction(Long playerId, String buildingType, int targetLevel,
                                               long startAt, long finishAt, Integer slot) {
        Construction c = new Construction();
        c.setPlayerId(playerId);
        c.setBuildingType(buildingType);
        c.setTargetLevel(targetLevel);
        c.setStartAt(startAt);
        c.setFinishAt(finishAt);
        c.setSlot(slot);
        return constructionRepository.save(c);
    }

    // ================================================================
    // Helper: Create a march record
    // ================================================================

    protected March createMarch(Long playerId, String targetKind, String targetId, String targetName,
                                int fromX, int fromY, int targetX, int targetY,
                                Map<String, Integer> army, String action,
                                long startAt, long arriveAt, boolean returning, boolean gathering) {
        March m = new March();
        m.setPlayerId(playerId);
        m.setTargetKind(targetKind);
        m.setTargetId(targetId);
        m.setTargetName(targetName);
        m.setFromX(fromX);
        m.setFromY(fromY);
        m.setTargetX(targetX);
        m.setTargetY(targetY);
        m.setDistance(Math.abs(fromX - targetX) + Math.abs(fromY - targetY));
        m.setAction(action);
        m.setArmy(JsonUtil.toJson(army));
        m.setCommanderId(null);
        m.setCarryRes(JsonUtil.toJson(Map.of("food", 0, "steel", 0, "oil", 0, "rare", 0)));
        m.setStartAt(startAt);
        m.setArriveAt(arriveAt);
        m.setReturning(returning);
        m.setGathering(gathering);
        m.setGatherEndAt(0L);
        m.setGatherAmount(0);
        m.setGatherRes(null);
        return marchRepository.save(m);
    }

    // ================================================================
    // Helper: Get resources for a player
    // ================================================================

    protected Resources getResources(Long playerId) {
        return resourcesRepository.findByPlayerId(playerId).orElse(null);
    }
}
