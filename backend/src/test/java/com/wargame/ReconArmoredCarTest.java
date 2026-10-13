package com.wargame;

import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.BattleService;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// 使用独立内存库，避免全量测试中其他 Spring 上下文关闭时删除本类的表。
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recon-armored-car;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class ReconArmoredCarTest extends BaseServiceTest {
    @Autowired ArmyService armyService;
    @Autowired ArmyProductionQueueRepository queues;
    private Player player;
    private WorldMap world;
    private WildTile tile;

    @BeforeEach void setup() {
        world = createTestWorld();
        world.setTerrainVersion(2);
        world.setTerrainData("0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE));
        worldMapRepository.save(world);
        player = createTestPlayer();
        giveResources(player.getId(), 100000, 100000, 100000, 100000, 100000);
        createArmyUnit(player.getId(), "recon_car", 2000);
        createArmyUnit(player.getId(), "scout", 2000);
        tile = createWildTile(world.getId(), "oil", 20, 10, 3, Map.of("infantry", 100), 5000);
    }

    private DispatchRequest request(Map<String, Integer> army) {
        return new DispatchRequest("wild", tile.getId(), "scout", army, null, null);
    }

    private Map<String, Object> report() {
        return JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(player.getId()).get(0).getData());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void bothModesRespectTheSameWildIntelligenceTierAndReturn(int level) {
        if (level > 0) createTechnology(player.getId(), "recon_level", level);
        for (String unit : new String[]{"recon_car", "scout"}) {
            March march = marchService.createDispatch(player.getId(), request(Map.of(unit, 1)));
            marchService.processMarches(player.getId(), march.getArriveAt());
            var data = scoutReportRepository.findByPlayerId(player.getId()).stream()
                    .map(r -> JsonUtil.parseObjMap(r.getData())).filter(r -> unit.equals(r.get("scoutUnit"))).findFirst().orElseThrow();
            assertEquals(true, data.get("showCityInfo"));
            assertEquals(level, data.get("reconLevel"));
            assertEquals(level >= 1, data.containsKey("army"));
            assertEquals(level == 0, data.containsKey("armyVague"));
            assertEquals(Map.of(unit, 1), data.get("mySurvivors"));
            assertEquals(1999, armyUnitRepository.findByPlayerIdAndType(player.getId(), unit).get(0).getCount());
            assertTrue(march.getReturning());
            marchService.processMarches(player.getId(), march.getArriveAt());
            assertEquals(2000, armyUnitRepository.findByPlayerIdAndType(player.getId(), unit).get(0).getCount());
        }
        marchService.processMarches(player.getId(), System.currentTimeMillis() + 1000000);
        assertEquals(2, scoutReportRepository.findByPlayerId(player.getId()).size());
        assertTrue(marchRepository.findByPlayerId(player.getId()).isEmpty());
    }

    @Test void invalidCompositionIsRejectedBeforeInventoryOrResourcesChange() {
        int oil = getResources(player.getId()).getOil();
        for (var army : java.util.List.of(Map.of("recon_car", 1, "scout", 1), Map.of("recon_car", 1, "infantry", 1),
                Map.of("recon_car", 1, "transport", 1), Map.of("unknown", 1))) {
            DispatchRequest req = request(army);
            assertThrows(IllegalArgumentException.class, () -> marchService.previewRoute(player.getId(), req));
            assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(player.getId(), req));
        }
        assertEquals(oil, getResources(player.getId()).getOil());
        assertEquals(2000, armyUnitRepository.findByPlayerIdAndType(player.getId(), "recon_car").get(0).getCount());
        assertTrue(marchRepository.findByPlayerId(player.getId()).isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void bothModesRespectAllCityIntelligencePermissions(int level) {
        if (level > 0) createTechnology(player.getId(), "recon_level", level);
        PlayerCity city = new PlayerCity();
        city.setWorldId(world.getId()); city.setX(20); city.setY(10); city.setName("侦查目标");
        city.setLevel(1); city.setArmy("{\"infantry\":10}"); city.setForts("{}"); city.setResources("{}");
        city = playerCityRepository.save(city);
        for (String unit : new String[]{"scout", "recon_car"}) {
            March march = marchService.createDispatch(player.getId(), new DispatchRequest("simulated_npc", city.getId(),
                    "scout", Map.of(unit, 1), null, null));
            marchService.processMarches(player.getId(), march.getArriveAt());
            var data = scoutReportRepository.findByPlayerId(player.getId()).stream()
                    .map(r -> JsonUtil.parseObjMap(r.getData())).filter(r -> unit.equals(r.get("scoutUnit"))).findFirst().orElseThrow();
            assertEquals(true, data.get("showCityInfo"));
            assertEquals(level >= 1, data.containsKey("forts"));
            assertEquals(level >= 2, data.containsKey("army"));
            assertEquals(level >= 3, data.containsKey("buildings"));
            assertEquals(level >= 4, data.containsKey("techs"));
            assertEquals(level >= 5, data.containsKey("officers"));
        }
    }

    @Test void groundReconRejectsSeaAndDisconnectedLandWhileAircraftCanReachThem() {
        char[] mask = world.getTerrainData().toCharArray();
        int size = WorldTerrainService.SIZE;
        for (int y = 0; y < size; y++) mask[y * size + 15] = '1';
        for (boolean seaTarget : new boolean[]{false, true}) {
            mask[10 * size + 20] = seaTarget ? '1' : '0';
            world.setTerrainData(new String(mask)); worldMapRepository.save(world);
            DispatchRequest car = request(Map.of("recon_car", 1));
            assertEquals("地面侦查无法抵达，请使用侦察机", assertThrows(IllegalArgumentException.class,
                    () -> marchService.previewRoute(player.getId(), car)).getMessage());
            assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(player.getId(), car));
            assertEquals("air", marchService.previewRoute(player.getId(), request(Map.of("scout", 1))).get("mode"));
        }
        // 普通陆军仍可被运输机投送，限制只作用于地面侦查。
        createArmyUnit(player.getId(), "transport", 1);
        var route = marchService.previewRoute(player.getId(), new DispatchRequest("wild", tile.getId(), "plunder",
                Map.of("recon_car", 10, "transport", 1), null, null));
        assertEquals("airlift", route.get("mode"));
        assertEquals(20, route.get("reservedLoad"));
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void oppositeModeAndNormalDefendersDoNotIntercept(String unit) {
        String opposite = "scout".equals(unit) ? "recon_car" : "scout";
        tile.setGarrison(JsonUtil.toJson(Map.of(opposite, 500, "armored", 500, "htank", 500)));
        wildTileRepository.save(tile);
        March march = marchService.createDispatch(player.getId(), request(Map.of(unit, 1)));
        marchService.processMarches(player.getId(), march.getArriveAt());
        assertEquals(0, report().get("enemyScouts"));
        assertEquals(0, report().get("myLost"));
        assertEquals(true, report().get("showCityInfo"));
        assertEquals(500, JsonUtil.parseIntMap(tile.getGarrison()).get(opposite));
        assertEquals(500, JsonUtil.parseIntMap(tile.getGarrison()).get("htank"));
    }

    @Test void groundDrawTransmitsIntelligenceAfterTenRounds() {
        tile.setGarrison(JsonUtil.toJson(Map.of("recon_car", 100, "scout", 30)));
        wildTileRepository.save(tile);
        March march = marchService.createDispatch(player.getId(), request(Map.of("recon_car", 100)));
        marchService.processMarches(player.getId(), march.getArriveAt());
        assertEquals("draw", report().get("result"));
        assertEquals(true, report().get("showCityInfo"));
        assertEquals(10, ((java.util.List<?>) report().get("combatLog")).size());
        assertTrue((Integer) report().get("myLost") > 0);
        assertTrue(march.getReturning());
        assertEquals(30, JsonUtil.parseIntMap(tile.getGarrison()).get("scout"));
    }

    @Test void annihilatedGroundReconReleasesCommanderAndDoesNotRevealDetails() {
        tile.setGarrison(JsonUtil.toJson(Map.of("recon_car", 100)));
        wildTileRepository.save(tile);
        Officer officer = createOfficer(player.getId(), "idle", 10, 10, 10);
        March march = marchService.createDispatch(player.getId(), new DispatchRequest("wild", tile.getId(), "scout",
                Map.of("recon_car", 1), officer.getId(), null));
        marchService.processMarches(player.getId(), march.getArriveAt());
        assertEquals(false, report().get("showCityInfo"));
        assertFalse(report().containsKey("army"));
        assertFalse(marchRepository.existsById(march.getId()));
        assertEquals("idle", officerRepository.findById(officer.getId()).orElseThrow().getRole());
    }

    @Test void recalledGroundReconReturnsWithoutReport() {
        March march = marchService.createDispatch(player.getId(), request(Map.of("recon_car", 10)));
        marchService.cancelMarch(player.getId(), march.getId());
        assertTrue(march.getReturning());
        marchService.processMarches(player.getId(), march.getArriveAt());
        assertTrue(scoutReportRepository.findByPlayerId(player.getId()).isEmpty());
        assertEquals(2000, armyUnitRepository.findByPlayerIdAndType(player.getId(), "recon_car").get(0).getCount());
    }

    @Test void compatibilityScoutUsesCarOnlyInventoryAndTheSameRouteValidation() {
        ArmyUnit aircraft = armyUnitRepository.findByPlayerIdAndType(player.getId(), "scout").get(0);
        aircraft.setCount(0); armyUnitRepository.save(aircraft);
        March march = marchService.createDefaultReconDispatch(player.getId(), "wild", tile.getId());
        assertEquals(Map.of("recon_car", 1), JsonUtil.parseIntMap(march.getArmy()));
        marchService.processMarches(player.getId(), march.getArriveAt());
        assertEquals("land", report().get("reconMode"));
        char[] terrain = world.getTerrainData().toCharArray();
        terrain[tile.getY() * WorldTerrainService.SIZE + tile.getX()] = '1';
        world.setTerrainData(new String(terrain)); worldMapRepository.save(world);
        int before = armyUnitRepository.findByPlayerIdAndType(player.getId(), "recon_car").get(0).getCount();
        assertEquals("地面侦查无法抵达，请使用侦察机", assertThrows(IllegalArgumentException.class,
                () -> marchService.createDefaultReconDispatch(player.getId(), "wild", tile.getId())).getMessage());
        assertEquals(before, armyUnitRepository.findByPlayerIdAndType(player.getId(), "recon_car").get(0).getCount());
    }

    @Test void speedUsesArmorEngineAndNeverApronBonus() {
        var car = marchService.previewRoute(player.getId(), request(Map.of("recon_car", 1)));
        var plane = marchService.previewRoute(player.getId(), request(Map.of("scout", 1)));
        assertEquals(1.8, ((Number) car.get("seconds")).doubleValue() / ((Number) plane.get("seconds")).doubleValue(), 0.1);
        createBuilding(player.getId(), "apron", 10);
        assertEquals(car.get("seconds"), marchService.previewRoute(player.getId(), request(Map.of("recon_car", 1))).get("seconds"));
        createTechnology(player.getId(), "arm_engine", 10);
        assertTrue(((Number) marchService.previewRoute(player.getId(), request(Map.of("recon_car", 1))).get("seconds")).intValue()
                < ((Number) car.get("seconds")).intValue());
    }

    @Test void manufactureUsesFactoryAndDocumentedResourcesAndPopulation() {
        player.setCivilianPopulation(10); playerRepository.save(player);
        createBuilding(player.getId(), "factory", 1); createBuilding(player.getId(), "house", 1);
        assertEquals(true, armyService.recruit(player.getId(), "recon_car", 1).get("success"));
        var queue = queues.findByPlayerIdOrderByStartedAtAscIdAsc(player.getId()).get(0);
        assertEquals("factory", queue.getBuildingType());
        assertEquals(40, queue.getCostFood()); assertEquals(180, queue.getCostSteel());
        assertEquals(60, queue.getCostOil()); assertEquals(20, queue.getCostRare());
        assertEquals(8, player.getCivilianPopulation());
    }

    @Test void participatesInOrdinaryGroundCombatWithoutAntiAircraftMultiplier() {
        assertEquals("armor", BattleRules.targetClass("recon_car"));
        assertEquals(3.0, BattleRules.multiplier("rocket", "recon_car"));
        assertEquals(1.0, BattleRules.multiplier("recon_car", "fighter"));
        assertEquals(1.0, BattleRules.multiplier("recon_car", "bomber"));
        assertEquals(5.0, UnitDef.UNITS.get("recon_car").atkAir());
        for (boolean defending : new boolean[]{false, true}) {
            var result = new BattleService(42).startWorldDispatch(
                    Map.of(defending ? "infantry" : "recon_car", 100), Map.of(defending ? "recon_car" : "infantry", 100),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0,
                    "plunder", Map.of(), 0, true);
            assertTrue(result.getReport().contains("侦查装甲车-灰狗（M8）"));
            assertTrue(result.getReport().contains("扫射"));
        }
    }
}
