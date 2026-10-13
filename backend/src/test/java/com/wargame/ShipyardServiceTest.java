package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.PrisonerUnitRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.PrerequisiteService;
import com.wargame.service.PrisonerService;
import com.wargame.service.TechService;
import com.wargame.service.WorldTerrainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证海军制造链的城域、施工占用、扣款顺序与历史队列兼容。 */
class ShipyardServiceTest extends BaseServiceTest {
    @Autowired private ArmyService army;
    @Autowired private ArmyProductionQueueRepository production;
    @Autowired private PrerequisiteService prerequisites;
    @Autowired private PrisonerService prisoners;
    @Autowired private PrisonerUnitRepository prisonerUnits;
    @Autowired private TechService tech;
    private Long playerId;
    private PlayerCity city;

    @BeforeEach
    void setUp() {
        WorldMap world = createTestWorld();
        char[] mask = new char[WorldTerrainService.SIZE * WorldTerrainService.SIZE];
        Arrays.fill(mask, '0');
        for (int y = 0; y < WorldTerrainService.SIZE; y++) {
            for (int x = 100; x < WorldTerrainService.SIZE; x++) mask[y * WorldTerrainService.SIZE + x] = '1';
        }
        world.setTerrainData(new String(mask));
        world.setTerrainVersion(2);
        worldMapRepository.save(world);
        Player player = createTestPlayer("shipyard-player", 30);
        player.setCivilianPopulation(1000);
        playerRepository.save(player);
        playerId = player.getId();
        city = new PlayerCity();
        city.setWorldId(world.getId());
        city.setOwnerId(playerId);
        city.setCitySlot(0);
        city.setName("海军基地");
        city.setX(98);
        city.setY(40);
        city.setLevel(1);
        city = playerCityRepository.save(city);
        giveResources(playerId, 1000000, 1000000, 1000000, 1000000, 1000000);
        createBuilding(playerId, "command", 10);
        createBuilding(playerId, "heavyfactory", 10);
        createBuilding(playerId, "refinery", 10);
        createBuilding(playerId, "transit", 10);
        createBuilding(playerId, "house", 1);
    }

    @Test
    void alternatingHarborAndShipyardUpgradesReachLevelTenWithoutDeadlock() {
        for (int level = 1; level <= 10; level++) {
            assertEquals(true, buildService.upgrade(playerId, "port", 0).get("success"), "军港 Lv." + level);
            buildService.completeUpgrade(playerId, System.currentTimeMillis() + 86401000L);
            assertEquals(true, buildService.upgrade(playerId, "shipyard", 0).get("success"), "造船厂 Lv." + level);
            buildService.completeUpgrade(playerId, System.currentTimeMillis() + 86401000L);
        }
        assertEquals(10, buildService.buildingLevel(playerId, "port"));
        assertEquals(10, buildService.buildingLevel(playerId, "shipyard"));
    }

    @Test
    void shipyardRequiresCompletedHarborAndIndustrialInfrastructureBeforePayment() {
        int steel = getResources(playerId).getSteel();
        var denied = buildService.upgrade(playerId, "shipyard", 0);
        assertEquals(false, denied.get("success"));
        assertTrue(denied.get("message").toString().contains("军港"));
        assertEquals(steel, getResources(playerId).getSteel());
        assertTrue(constructionRepository.findByPlayerId(playerId).isEmpty());
        assertEquals(true, buildService.upgrade(playerId, "port", 0).get("success"));
        assertEquals(false, buildService.upgrade(playerId, "shipyard", 0).get("success"));
        buildService.completeUpgrade(playerId, System.currentTimeMillis() + 60000L);
        assertEquals(true, buildService.upgrade(playerId, "shipyard", 0).get("success"));
    }

    @Test
    void newInlandShipyardIsRejectedWhileLegacyNavalCityRetainsEligibility() {
        city.setX(20);
        playerCityRepository.save(city);
        createBuilding(playerId, "port", 1);
        var denied = buildService.upgrade(playerId, "shipyard", 0);
        assertEquals(false, denied.get("success"));
        assertTrue(denied.get("message").toString().contains("沿海"));
        city.setLegacyNaval(true);
        playerCityRepository.save(city);
        assertEquals(true, buildService.upgrade(playerId, "shipyard", 0).get("success"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"destroyer", "sub", "battleship", "carrier"})
    void everyNavalUnitNeedsBothLocalBuildingsAndSchedulesAtShipyard(String unit) {
        createBuilding(playerId, "port", 1);
        int steel = getResources(playerId).getSteel();
        int civilians = playerRepository.findById(playerId).orElseThrow().getCivilianPopulation();
        var denied = army.recruit(playerId, unit, 1);
        assertEquals(false, denied.get("success"));
        assertTrue(denied.get("message").toString().contains("造船厂"));
        assertEquals(0, denied.get("maxRecruitable"));
        assertEquals(steel, getResources(playerId).getSteel());
        assertEquals(civilians, playerRepository.findById(playerId).orElseThrow().getCivilianPopulation());
        assertTrue(production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).isEmpty());
        createBuilding(playerId, "shipyard", 1);
        var accepted = army.recruit(playerId, unit, 1);
        assertEquals(true, accepted.get("success"));
        assertEquals("shipyard", production.findById((Long) accepted.get("queueId")).orElseThrow().getBuildingType());
    }

    @Test
    void shipyardAloneCannotRecruitAndAnotherCityCannotSupplyEitherBuilding() {
        Building shipyard = createBuilding(playerId, "shipyard", 5);
        var missing = prerequisites.unmetRecruitment(playerId, 0, "destroyer");
        assertEquals("port", missing.get(0).get("building"));
        assertEquals(false, army.recruit(playerId, "destroyer", 1).get("success"));
        Building otherPort = createBuilding(playerId, "port", 5);
        otherPort.setCitySlot(1);
        buildingRepository.save(otherPort);
        assertEquals(false, army.recruit(playerId, "destroyer", 1).get("success"));
        otherPort.setCitySlot(0);
        shipyard.setCitySlot(1);
        buildingRepository.save(otherPort);
        buildingRepository.save(shipyard);
        assertEquals(false, army.recruit(playerId, "destroyer", 1).get("success"));
    }

    @Test
    void pendingConstructionCannotEnableRecruitmentAndPendingDemolitionDisablesIt() {
        createBuilding(playerId, "port", 1);
        assertEquals(true, buildService.upgrade(playerId, "shipyard", 0).get("success"));
        assertEquals(false, army.recruit(playerId, "destroyer", 1).get("success"));
        buildService.completeUpgrade(playerId, System.currentTimeMillis() + 60000L);
        assertEquals(true, buildService.dismantle(playerId, "shipyard", 0).get("success"));
        assertEquals(false, army.recruit(playerId, "destroyer", 1).get("success"));
    }

    @Test
    void shipyardLevelControlsProductionSpeedAndParallelism() {
        createBuilding(playerId, "port", 10);
        Building shipyard = createBuilding(playerId, "shipyard", 1);
        var slow = army.recruit(playerId, "destroyer", 1);
        shipyard.setLevel(5);
        buildingRepository.save(shipyard);
        var fast = army.recruit(playerId, "destroyer", 1);
        assertEquals(1, slow.get("parallelQueues"));
        assertEquals(2, fast.get("parallelQueues"));
        assertTrue((int) fast.get("durationSeconds") < (int) slow.get("durationSeconds"));
    }

    @Test
    void navalQueuesProtectBothBuildingsUntilCancellation() {
        createBuilding(playerId, "port", 1);
        createBuilding(playerId, "shipyard", 1);
        var recruited = army.recruit(playerId, "destroyer", 1);
        for (String building : List.of("port", "shipyard")) {
            var denied = buildService.dismantle(playerId, building, 0);
            assertEquals(false, denied.get("success"));
            assertTrue(denied.get("message").toString().contains("生产中的"));
        }
        assertTrue(army.cancelProduction(playerId, (Long) recruited.get("queueId")));
        assertEquals(true, buildService.dismantle(playerId, "shipyard", 0).get("success"));
    }

    @Test
    void paidUpgradeSnapshotsProtectLinkedBuildingLevels() {
        createBuilding(playerId, "port", 1);
        createBuilding(playerId, "shipyard", 1);
        assertEquals(true, buildService.upgrade(playerId, "port", 0).get("success"));
        assertEquals(false, buildService.dismantle(playerId, "shipyard", 0).get("success"));
        buildService.completeUpgrade(playerId, System.currentTimeMillis() + 600000L);
        assertEquals(true, buildService.upgrade(playerId, "shipyard", 0).get("success"));
        assertEquals(false, buildService.dismantle(playerId, "port", 0).get("success"));
    }

    @Test
    void navalEngineResearchNeedsMatchingShipyardLevelAndProtectsIt() {
        createBuilding(playerId, "port", 5);
        Building shipyard = createBuilding(playerId, "shipyard", 4);
        createBuilding(playerId, "lab", 5);
        createBuilding(playerId, "oilfield", 5);
        var missing = prerequisites.unmet(playerId, 0, "technologies", "nav_engine", 5);
        assertEquals(1, missing.size());
        assertEquals("shipyard", missing.get(0).get("building"));
        shipyard.setLevel(5);
        buildingRepository.save(shipyard);
        assertTrue(prerequisites.unmet(playerId, 0, "technologies", "nav_engine", 5).isEmpty());
        Technology previous = new Technology();
        previous.setPlayerId(playerId);
        previous.setType("nav_engine");
        previous.setLevel(4);
        technologyRepository.save(previous);
        assertEquals(true, tech.upgrade(playerId, "nav_engine").get("success"));
        assertEquals(false, buildService.dismantle(playerId, "shipyard", 0).get("success"));
    }

    @Test
    void legacyPortQueueCompletesWithoutAddingShipyardOrChangingExistingFleet() {
        createBuilding(playerId, "port", 1);
        Building shipyard = createBuilding(playerId, "shipyard", 1);
        createArmyUnit(playerId, "destroyer", 7);
        var recruited = army.recruit(playerId, "destroyer", 1);
        ArmyProductionQueue queue = production.findById((Long) recruited.get("queueId")).orElseThrow();
        queue.setBuildingType("port");
        production.save(queue);
        buildingRepository.delete(shipyard);
        assertEquals(false, buildService.dismantle(playerId, "port", 0).get("success"));
        army.completeProduction(playerId, queue.getFinishesAt() + 1L);
        assertTrue(production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).isEmpty());
        assertEquals(8, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "destroyer").get(0).getCount());
        assertEquals(0, buildService.buildingLevel(playerId, "shipyard"));
        assertEquals(false, army.recruit(playerId, "destroyer", 1).get("success"));
    }

    @Test
    void legacyPortQueueStillOccupiesShipyardProductionLane() {
        createBuilding(playerId, "port", 1);
        createBuilding(playerId, "shipyard", 1);
        var first = army.recruit(playerId, "destroyer", 1);
        ArmyProductionQueue legacy = production.findById((Long) first.get("queueId")).orElseThrow();
        legacy.setBuildingType("port");
        production.save(legacy);
        var second = army.recruit(playerId, "sub", 1);
        ArmyProductionQueue queued = production.findById((Long) second.get("queueId")).orElseThrow();
        assertEquals(legacy.getFinishesAt(), queued.getStartedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"port", "shipyard"})
    void navalPrisonerRecruitmentRequiresBothBuildingsBeforeChargingGold(String presentBuilding) {
        createBuilding(playerId, presentBuilding, 1);
        PrisonerUnit batch = prisoner();
        int gold = getResources(playerId).getGold();
        var pool = prisoners.getPool(playerId);
        var units = (List<Map<String, Object>>) pool.get("units");
        assertFalse(units.get(0).get("unavailableReason").toString().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> prisoners.operate(playerId, "recruit", "destroyer", 1, "navy-denied-123"));
        assertEquals(gold, getResources(playerId).getGold());
        assertEquals(2, prisonerUnits.findById(batch.getId()).orElseThrow().getCount());
    }

    @Test
    void navalPrisonersCanBeRecruitedWithBothBuildings() {
        createBuilding(playerId, "port", 1);
        createBuilding(playerId, "shipyard", 1);
        prisoner();
        assertEquals(true, prisoners.operate(playerId, "recruit", "destroyer", 1, "navy-success-123").get("success"));
        assertEquals(1, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "destroyer").get(0).getCount());
    }

    private PrisonerUnit prisoner() {
        PrisonerUnit batch = new PrisonerUnit();
        batch.setPlayerId(playerId);
        batch.setCityId(city.getId());
        batch.setCitySlot(0);
        batch.setSettlementId(1L);
        batch.setSourceId("navy-test");
        batch.setOriginalOwnerId(999L);
        batch.setType("destroyer");
        batch.setOriginalCount(2);
        batch.setCount(2);
        batch.setCreatedAt(System.currentTimeMillis());
        return prisonerUnits.save(batch);
    }
}
