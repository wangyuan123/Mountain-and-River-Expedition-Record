package com.wargame;

import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.entity.*;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.OfficerEquipmentRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.BattleService;
import com.wargame.service.UnitEquipmentRecon;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconArmoredCarTest extends BaseServiceTest {
    @Autowired ArmyService armyService;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired OfficerEquipmentRepository equipment;
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

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void actualResearchAndOpeningRadarReachBothReportPerspectives(String unit) {
        createTechnology(player.getId(), "recon_level", 4);
        Player defender = createTestPlayer("radar-defender", 30);
        createTechnology(defender.getId(), "recon_level", 2);
        createTechnology(defender.getId(), "recon_radar", 3);
        PlayerCity target = new PlayerCity();
        target.setOwnerId(defender.getId()); target.setCitySlot(0); target.setWorldId(world.getId());
        target.setName("雷达拦截城市"); target.setX(20); target.setY(10); target.setLevel(1);
        target = playerCityRepository.saveAndFlush(target);
        createArmyUnit(defender.getId(), unit, 1000);
        March march = marchService.createDispatch(player.getId(), new DispatchRequest("player", target.getId(),
                "scout", Map.of(unit, 1000), null, null));
        marchService.processMarches(player.getId(), march.getArriveAt());
        Map<String, Object> attack = report();
        Map<String, Object> defense = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(defender.getId()).get(0).getData());
        Map<String, Object> initiative = Map.of("version", UnitEquipmentRecon.INITIATIVE_RULES_VERSION,
                "attackerReconLevel", 4, "defenderReconLevel", 2, "defenderRadarLevel", 3);
        assertEquals(initiative, attack.get("reconInitiative"));
        assertEquals(initiative, defense.get("reconInitiative"));
        int speed = UnitDef.UNITS.get(unit).spd();
        assertEquals(speed + 20, attack.get("attackerInitiative"));
        assertEquals(speed + 16, attack.get("defenderInitiative"));
        assertEquals("attacker", attack.get("firstStrike"));
        assertEquals(attack.get("firstStrike"), defense.get("firstStrike"));
        List<?> logs = (List<?>) attack.get("combatLog"), defenseLogs = (List<?>) defense.get("combatLog");
        assertEquals(10, logs.size());
        assertTrue(logs.get(0).toString().contains("本回合先手：我方"));
        assertTrue(defenseLogs.get(0).toString().contains("我方雷达预警：先手+6（仅首回合）"));
        for (int round = 2; round <= 10; round++) {
            assertTrue(logs.get(round - 1).toString().contains("本回合先手：我方（我方" + (speed + 20) + " / 敌方" + (speed + 10) + "）"));
            assertTrue(defenseLogs.get(round - 1).toString().contains("本回合先手：敌方"));
            assertFalse(logs.get(round - 1).toString().contains("雷达预警"));
        }
        assertEquals(attack.get("mySurvivors"), defense.get("enemySurvivors"));
        assertEquals(attack.get("enemySurvivors"), defense.get("mySurvivors"));
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void actualLeadersEquipmentAndFirstCombatCycleReachBothReportPerspectives(String unit) {
        Officer home = createOfficer(player.getId(), "commander", 200, 200, 10, 10);
        home.setName("不能借用的本城守将"); officerRepository.save(home);
        Officer leader = createOfficer(player.getId(), "idle", 70, 40, 10, 10);
        leader.setName("实际随队将领"); leader.setSkills("[{\"id\":\"frenzy\",\"lv\":5}]");
        officerRepository.saveAndFlush(leader);
        for (String slot : List.of("weapon", "badge", "coat")) equip(leader, "recruit_military_" + slot);
        Player defender = createTestPlayer("recon-defender", 30);
        Officer main = createOfficer(defender.getId(), "commander", 210, 210, 10, 10);
        main.setName("不能串用的主城守将"); officerRepository.save(main);
        Officer guard = createOfficer(defender.getId(), "commander", 55, 77, 10, 10);
        guard.setCitySlot(1); guard.setName("实际分城守将");
        guard.setSkills("[{\"id\":\"bulwark\",\"lv\":3}]"); officerRepository.saveAndFlush(guard);
        PlayerCity target = new PlayerCity();
        target.setOwnerId(defender.getId()); target.setCitySlot(1); target.setWorldId(world.getId());
        target.setName("目标分城"); target.setX(20); target.setY(10); target.setLevel(1);
        target = playerCityRepository.saveAndFlush(target);
        ArmyUnit garrison = createArmyUnit(defender.getId(), unit, 1000);
        garrison.setCitySlot(1); armyUnitRepository.saveAndFlush(garrison);

        March march = marchService.createDispatch(player.getId(), new DispatchRequest("player", target.getId(),
                "scout", Map.of(unit, 1000), leader.getId(), null));
        marchService.processMarches(player.getId(), march.getArriveAt());
        Map<String, Object> attack = report();
        Map<String, Object> defense = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(defender.getId()).get(0).getData());
        Map<?, ?> commanders = (Map<?, ?>) attack.get("commanders");
        Map<?, ?> a = (Map<?, ?>) commanders.get("attacker"), d = (Map<?, ?>) commanders.get("defender");
        assertEquals("实际随队将领", a.get("name")); assertEquals(70, a.get("baseMilitary"));
        assertEquals(88, a.get("military")); assertEquals(40, a.get("baseDefense")); assertEquals(43, a.get("defense"));
        assertEquals("frenzy", ((Map<?, ?>) ((List<?>) a.get("skills")).get(0)).get("id"));
        assertEquals("实际分城守将", d.get("name")); assertEquals(55, d.get("military")); assertEquals(77, d.get("defense"));
        assertEquals(commanders, defense.get("commanders")); assertEquals("defender", defense.get("perspective"));
        assertEquals(List.of(1, 4, 7, 10), attack.get("commanderAttributeRounds"));
        assertEquals(attack.get("commanderAttributeRounds"), attack.get("commanderSkillRounds"));
        assertEquals(attack.get("commanderAttributeRounds"), defense.get("commanderAttributeRounds"));
        List<?> logs = (List<?>) attack.get("combatLog");
        assertEquals(10, logs.size());
        for (int round = 1; round <= 10; round++) {
            String log = logs.get(round - 1).toString();
            assertEquals((round - 1) % 3 == 0, log.contains("军事属性 +88%攻击"));
            assertEquals((round - 1) % 3 == 0, log.contains("全军冲锋"));
            assertEquals((round - 1) % 3 == 0, log.contains("坚守阵地"));
        }
        // 首回合实际战损核对总属性只计一次，且无需等到第3回合才能参与。
        var stats = UnitDef.UNITS.get(unit);
        double base = "scout".equals(unit) ? stats.atkAir() : stats.atkGround();
        int enemy = 1000 - (int) Math.floor(1000 * base * 1.88 * 1.5 / (stats.hp() + stats.def() * 1.77 * 1.3)) - 1;
        int own = 1000 - (int) Math.floor(enemy * base * 1.55 / (stats.hp() + stats.def() * 1.43)) - 1;
        assertTrue(logs.get(0).toString().startsWith("第1回合：我方剩余" + own + "，敌方剩余" + enemy));
        String defenseLog = ((List<?>) defense.get("combatLog")).get(0).toString();
        assertTrue(defenseLog.contains("我方将领加成：军事属性 +55%攻击"));
        assertTrue(defenseLog.contains("敌方将领加成：军事属性 +88%攻击"));
        assertEquals(attack.get("mySurvivors"), defense.get("enemySurvivors"));
        assertEquals(attack.get("enemySurvivors"), defense.get("mySurvivors"));
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void unselectedHomeCommanderDoesNotJoinRecon(String unit) {
        Officer home = createOfficer(player.getId(), "commander", 219, 219, 10, 10);
        home.setSkills("[{\"id\":\"frenzy\",\"lv\":5}]"); officerRepository.saveAndFlush(home);
        tile.setGarrison(JsonUtil.toJson(Map.of(unit, 100))); wildTileRepository.saveAndFlush(tile);
        March march = marchService.createDispatch(player.getId(), request(Map.of(unit, 100)));
        marchService.processMarches(player.getId(), march.getArriveAt());
        assertNull(((Map<?, ?>) report().get("commanders")).get("attacker"));
        var baseline = UnitEquipmentRecon.resolve(unit, 100, Map.of(unit, 100), Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, 0, 0, 0);
        assertEquals(baseline.ownAlive(), ((Map<?, ?>) report().get("mySurvivors")).get(unit));
        assertTrue(((List<?>) report().get("combatLog")).stream().noneMatch(log -> log.toString().contains("将领加成")));
    }

    private void equip(Officer officer, String key) {
        OfficerEquipmentDef def = OfficerEquipmentDef.ITEMS.get(key);
        OfficerEquipment item = new OfficerEquipment();
        item.setPlayerId(officer.getPlayerId()); item.setOfficerId(officer.getId()); item.setItemKey(key);
        item.setSetType(def.setKey()); item.setTier(def.tier()); item.setSlot(def.slot());
        item.setMilitaryBonus(def.military()); item.setDefenseBonus(def.defense());
        item.setLogisticsBonus(def.logistics()); item.setKnowledgeBonus(def.knowledge());
        item.setCreatedAt(0L); item.setEquippedAt(0L); equipment.saveAndFlush(item);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
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

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
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
            assertEquals("recon-intelligence-v2-20261019", data.get("reconIntelligenceVersion"));
            assertEquals(level >= 1, data.containsKey("fortTypes"));
            assertEquals(level >= 1, data.containsKey("wallPresent"));
            assertEquals(level >= 2, data.containsKey("wallLevel"));
            assertEquals(level >= 2, data.containsKey("forts"));
            assertEquals(level >= 4, data.containsKey("commander"));
            assertEquals(level >= 6, data.containsKey("officerCount"));
            assertEquals(level >= 8, data.containsKey("plunderable"));
            assertEquals(level >= 10, data.containsKey("defensePower"));
            assertEquals(level >= 3, data.containsKey("army"));
            assertEquals(level >= 5, data.containsKey("buildings"));
            assertEquals(level >= 7, data.containsKey("techs"));
            assertEquals(level >= 9, data.containsKey("officers"));
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
        assertEquals(9.0 / 5.0, ((Number) car.get("seconds")).doubleValue() / ((Number) plane.get("seconds")).doubleValue(), 0.1);
        createBuilding(player.getId(), "apron", 10);
        assertEquals(car.get("seconds"), marchService.previewRoute(player.getId(), request(Map.of("recon_car", 1))).get("seconds"));
        createTechnology(player.getId(), "arm_engine", 10);
        assertTrue(((Number) marchService.previewRoute(player.getId(), request(Map.of("recon_car", 1))).get("seconds")).intValue()
                < ((Number) car.get("seconds")).intValue());
    }

    @Test void manufactureUsesFactoryAndDocumentedResourcesAndPopulation() {
        player.setCivilianPopulation(10); playerRepository.save(player);
        createBuilding(player.getId(), "factory", 2); createBuilding(player.getId(), "house", 1);
        createTechnology(player.getId(), "recon_level", 1);
        assertEquals(true, armyService.recruit(player.getId(), "recon_car", 1).get("success"));
        var queue = queues.findByPlayerIdOrderByStartedAtAscIdAsc(player.getId()).get(0);
        assertEquals("factory", queue.getBuildingType());
        // 沿用当前资源造价设计，解锁门槛不会另行收取费用。
        assertEquals(40, queue.getCostFood()); assertEquals(270, queue.getCostSteel());
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
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, 0,
                    "plunder", Map.of(), 0, true);
            assertTrue(result.getReport().contains("侦查装甲车-灰狗（M8）"));
            assertTrue(result.getReport().contains("扫射"));
        }
    }
}
