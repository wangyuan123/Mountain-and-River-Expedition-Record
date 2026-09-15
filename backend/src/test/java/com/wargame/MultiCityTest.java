package com.wargame;

import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiCityTest extends BaseServiceTest {
    @Autowired CityService cities;
    @Autowired CityScope scope;
    @Autowired MarchTargetService targets;
    @Autowired ArmyService army;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired WoundedService wounded;
    private Player player;
    private PlayerCity main;
    private WorldMap world;

    @BeforeEach void setup() {
        world = createTestWorld(); player = createTestPlayer("multicity", 30);
        player.setMilitaryRank(7); player.setCivilianPopulation(50); playerRepository.save(player);
        giveResources(player.getId(), 100000, 100000, 100000, 100000, 100000);
        main = new PlayerCity(); main.setOwnerId(player.getId()); main.setCitySlot(0); main.setName("主城");
        main.setWorldId(world.getId()); main.setX(10); main.setY(10); main.setLevel(1); playerCityRepository.save(main);
        createBuilding(player.getId(), "house", 1); createBuilding(player.getId(), "farm", 1); createBuilding(player.getId(), "command", 1);
    }
    private WildTile site(int x) {
        WildTile tile = new WildTile(); tile.setWorldId(world.getId()); tile.setX(x); tile.setY(20);
        tile.setType("hill"); tile.setOccupied(true); tile.setOccupiedBy(player.getId()); tile.setLevel(1);
        return wildTileRepository.save(tile);
    }
    private PlayerCity branch() {
        PlayerCity city = cities.found(player.getId(), site(20).getId(), "北山城");
        city.setReadyAt(0L); city.setLastTick(System.currentTimeMillis()); playerCityRepository.save(city); return city;
    }
    private Resources balance(int slot) { return resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), slot).orElseThrow(); }
    private int troop(int slot) { return armyUnitRepository.findByPlayerIdAndCitySlotAndType(player.getId(), slot, "infantry").stream().mapToInt(ArmyUnit::getCount).sum(); }

    @Test void allSeventeenRanksHaveTheAgreedCap() {
        int[] caps = {1,1,1,2,2,2,3,3,3,4,4,4,5,5,6,7,8};
        for (int i = 1; i <= 17; i++) assertEquals(caps[i - 1], MilitaryRankDef.getCityCap(i));
        assertEquals("中士", MilitaryRankDef.nextCityRank(1).name());
        assertNull(MilitaryRankDef.nextCityRank(17));
    }

    @Test void foundingReservesSlotsChargesOnceAndRequiresAnOwnedSuitableSite() {
        player.setMilitaryRank(4); playerRepository.save(player);
        WildTile tile = site(20);
        PlayerCity city = cities.found(player.getId(), tile.getId(), "北山城");
        assertTrue(city.getReadyAt() > System.currentTimeMillis());
        assertFalse(wildTileRepository.existsById(tile.getId()));
        assertEquals(90000, balance(0).getGold());
        assertEquals(1000, balance(1).getGold());
        assertThrows(IllegalArgumentException.class, () -> cities.select(player.getId(), city.getId()));
        assertThrows(IllegalArgumentException.class, () -> cities.found(player.getId(), site(30).getId(), "第三城"));
        assertEquals(90000, balance(0).getGold());
        assertEquals(2, cities.overview(player.getId()).get("count"));
    }

    @Test void invalidSiteAndPrestigeAloneNeverUnlockCities() {
        player.setMilitaryRank(1); player.setPrestige(2500000); playerRepository.save(player);
        assertThrows(IllegalArgumentException.class, () -> cities.found(player.getId(), site(20).getId(), "分城"));
        player.setMilitaryRank(7); playerRepository.save(player);
        WildTile foreign = site(21); foreign.setOccupiedBy(999L); wildTileRepository.save(foreign);
        assertThrows(IllegalArgumentException.class, () -> cities.found(player.getId(), foreign.getId(), "分城"));
        WildTile swamp = site(22); swamp.setType("swamp"); wildTileRepository.save(swamp);
        assertThrows(IllegalArgumentException.class, () -> cities.found(player.getId(), swamp.getId(), "分城"));
        assertEquals(100000, balance(0).getGold());
    }

    @Test @SuppressWarnings("unchecked") void switchingKeepsMainEconomyAndSharesTechAndDiamonds() {
        PlayerCity branch = branch();
        balance(0).setDiamond(987); createTechnology(player.getId(), "log_food", 3);
        cities.select(player.getId(), branch.getId());
        gameStateService.setTax(player.getId(), 10);
        gameStateService.setCityName(player.getId(), "新北山城");
        Map<String, Object> state = gameStateService.getGameState(player.getId());
        assertEquals(1000, ((Map<String,Object>)state.get("resources")).get("gold"));
        assertEquals(987, ((Map<String,Object>)state.get("resources")).get("diamond"));
        assertEquals(3, ((Map<String,Object>)state.get("tech")).get("log_food"));
        assertEquals(10, branch.getTax()); assertEquals(30, player.getTax());
        assertEquals("TestCity", player.getCityName()); assertEquals(10, player.getCityPosX());
        assertEquals("新北山城", branch.getName());
        assertThrows(IllegalArgumentException.class, () -> cities.select(999L, branch.getId()));
        try (var ignored = scope.enter(player.getId(), 0)) {
            assertEquals(0, scope.slot(player.getId()));
            assertEquals(30, scope.economy(player.getId()).getTax());
        }
        assertEquals(1, scope.slot(player.getId()));
    }

    @Test void ticksAdvanceEveryReadyCityAndKeepProductionAndPopulationSeparate() {
        PlayerCity branch = branch();
        balance(0).setFood(0); balance(1).setFood(0);
        long before = System.currentTimeMillis() - 3600000;
        player.setLastTick(before); branch.setLastTick(before);
        ArmyProductionQueue queue = new ArmyProductionQueue(); queue.setPlayerId(player.getId()); queue.setCitySlot(1);
        queue.setUnitType("infantry"); queue.setUnitCount(5); queue.setStartedAt(before); queue.setFinishesAt(before + 1000);
        queue.setDurationSeconds(1); queue.setSpeedMultiplier(1.0); queues.save(queue);
        cities.select(player.getId(), main.getId()); tickService.tick(player.getId());
        assertTrue(balance(0).getFood() > 0); assertTrue(balance(1).getFood() > 0);
        assertTrue(branch.getCivilianPopulation() > 50); assertTrue(player.getCivilianPopulation() > 50);
        assertEquals(5, troop(1)); assertEquals(0, troop(0));
        assertTrue(queues.findById(queue.getId()).isEmpty());
    }

    @Test void buildingJobsBelongToTheInitiatingCity() {
        PlayerCity branch = branch();
        cities.select(player.getId(), branch.getId());
        assertEquals(true, buildService.upgrade(player.getId(), "command", 0).get("success"));
        Construction job = constructionRepository.findByPlayerIdAndCitySlot(player.getId(), 1).get(0);
        job.setFinishAt(0L);
        cities.select(player.getId(), main.getId());
        try (var ignored = scope.enter(branch)) { buildService.completeUpgrade(player.getId(), System.currentTimeMillis()); }
        assertEquals(1, buildService.buildingLevel(player.getId(), "command", 0));
        try (var ignored = scope.enter(branch)) { assertEquals(2, buildService.buildingLevel(player.getId(), "command", 0)); }
    }

    @Test void returningArmyUsesOriginDespiteSelectionAndCannotBeCancelledFromAnotherCity() {
        PlayerCity branch = branch(); createArmyUnit(player.getId(), "infantry", 20); createArmyUnit(player.getId(), "truck", 2);
        WildTile target = site(40); target.setOccupied(false); target.setOccupiedBy(null); target.setGarrison("{}"); wildTileRepository.save(target);
        player.setPosX(80); player.setPosY(80);
        March march = marchService.createDispatch(player.getId(), new DispatchRequest("wild", target.getId(), "conquer", Map.of("infantry",10), null, null));
        assertEquals(10, march.getFromX()); assertEquals(0, march.getCitySlot());
        cities.select(player.getId(), branch.getId());
        assertThrows(IllegalArgumentException.class, () -> marchService.cancelMarch(player.getId(), march.getId()));
        march.setReturning(true); march.setArriveAt(0L); march.setCarryRes("{\"gold\":100}");
        try (var ignored = scope.enter(player.getId(), 0)) { marchService.processMarches(player.getId(), System.currentTimeMillis()); }
        assertEquals(20, troop(0)); assertEquals(0, troop(1)); assertEquals(90100, balance(0).getGold()); assertEquals(1000, balance(1).getGold());
    }

    @Test void rebaseMovesTroopsResourcesAndOfficerOnlyOnArrival() {
        PlayerCity branch = branch(); createArmyUnit(player.getId(), "infantry", 20); createArmyUnit(player.getId(), "truck", 2);
        Officer officer = createOfficer(player.getId(), "idle", 10, 10, 10);
        March march = marchService.createDispatch(player.getId(), new DispatchRequest("player", branch.getId(), "rebase", Map.of("infantry",10,"truck",1), officer.getId(), Map.of("gold",5)));
        assertEquals(10, troop(0)); assertEquals(0, troop(1)); assertEquals("march", officer.getRole());
        assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(player.getId(), new DispatchRequest("player", branch.getId(), "transport", Map.of("infantry",1), officer.getId(), Map.of())));
        march.setArriveAt(0L); marchService.processMarches(player.getId(), System.currentTimeMillis());
        assertEquals(10, troop(1)); assertEquals(1005, balance(1).getGold()); assertEquals(1, officer.getCitySlot()); assertEquals("idle", officer.getRole());
        assertTrue(marchRepository.findById(march.getId()).isEmpty());
    }

    @Test void transportDeliversCargoThenReturnsTroopsToOrigin() {
        PlayerCity branch = branch(); createArmyUnit(player.getId(), "infantry", 20); createArmyUnit(player.getId(), "truck", 2);
        March march = marchService.createDispatch(player.getId(), new DispatchRequest("player", branch.getId(), "transport", Map.of("infantry",10,"truck",1), null, Map.of("gold",5)));
        march.setArriveAt(0L); marchService.processMarches(player.getId(), System.currentTimeMillis());
        assertEquals(1005, balance(1).getGold()); assertEquals(0, troop(1)); assertTrue(march.getReturning());
        cities.select(player.getId(), branch.getId());
        march.setArriveAt(0L);
        try (var ignored = scope.enter(player.getId(), 0)) { marchService.processMarches(player.getId(), System.currentTimeMillis()); }
        assertEquals(20, troop(0)); assertEquals(0, troop(1)); assertEquals(1005, balance(1).getGold());
    }

    @Test void scoutingAndTreatmentUseActualTargetCityNotDefenderSelection() {
        PlayerCity branch = branch();
        createArmyUnit(player.getId(), "infantry", 100);
        ArmyUnit local = createArmyUnit(player.getId(), "infantry", 7); local.setCitySlot(1); armyUnitRepository.save(local);
        assertEquals(Map.of("infantry",7), targets.getTargetArmy(branch));
        assertEquals(1000, targets.getTargetResources(branch).get("gold"));
        createTechnology(player.getId(), "log_medical", 10);
        wounded.recordLosses(player.getId(), branch.getX(), branch.getY(), Map.of("infantry",10), Map.of(), null, System.currentTimeMillis());
        try (var ignored = scope.enter(branch)) {
            @SuppressWarnings("unchecked") var batches = (List<Map<String,Object>>)wounded.getCamp(player.getId()).get("batches");
            Long batchId = ((Number)batches.get(0).get("id")).longValue();
            wounded.heal(player.getId(), batchId, 5, "gold");
        }
        assertEquals(12, troop(1)); assertEquals(100, troop(0));
    }

    @Test void battleDamagesOnlyTheTargetCityWhileDefenderViewsMainCity() {
        PlayerCity branch = branch();
        createArmyUnit(player.getId(), "infantry", 100);
        ArmyUnit garrison = createArmyUnit(player.getId(), "infantry", 1); garrison.setCitySlot(1); armyUnitRepository.save(garrison);
        Player attacker = createTestPlayer("city-attacker", 30);
        long now = System.currentTimeMillis();
        createMarch(attacker.getId(), "player", branch.getId().toString(), branch.getName(), 10, 10, branch.getX(), branch.getY(),
                Map.of("infantry",1000), "conquer", now - 60000, now - 1, false, false);
        marchService.processMarches(attacker.getId(), now);
        assertEquals(100, troop(0)); assertEquals(0, troop(1));
        assertEquals(90000, balance(0).getGold()); assertEquals(0, balance(1).getGold());
        assertEquals(player.getId(), branch.getOwnerId());
        assertEquals(1L, scoutReportRepository.countUnreadByPlayerId(player.getId()));
    }
}
