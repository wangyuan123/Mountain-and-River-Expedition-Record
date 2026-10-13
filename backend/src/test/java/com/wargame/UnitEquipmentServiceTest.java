package com.wargame;

import com.wargame.model.constants.*;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UnitEquipmentServiceTest extends BaseServiceTest {
    @Autowired UnitEquipmentService equipment;
    @Autowired UnitLoadoutRepository loadouts;
    @Autowired UnitEquipmentOrderRepository orders;
    @Autowired PlayerItemRepository items;
    @Autowired CityScope scope;
    @Autowired GuildRepository guilds;
    @Autowired GuildMemberRepository members;
    @Autowired GuildSupportService support;
    @Autowired MarchTargetService targets;
    @Autowired BattleSessionRepository battles;
    @Autowired MarchRouteService routes;

    private void item(Long player, String key) { PlayerItem i = new PlayerItem(); i.setPlayerId(player); i.setItemKey(key); i.setCount(1); i.setUpdatedAt(1L); items.save(i); }
    private int inventory(Long player, String key) { return items.findByPlayerIdAndItemKey(player, key).map(PlayerItem::getCount).orElse(0); }
    private void tech(Long player, int level) { Technology t = new Technology(); t.setPlayerId(player); t.setType("unit_refit"); t.setLevel(level); technologyRepository.save(t); }
    @Test void oneEquipmentAppliesAcrossCitiesWithoutTroopCountAndSurvivesAnnihilation() {
        Player p = createTestPlayer(); item(p.getId(), "ue_infantry_helmet");
        equipment.save(p.getId(), "infantry", Map.of("protection", "ue_infantry_helmet"), 0L);
        assertEquals(0, inventory(p.getId(), "ue_infantry_helmet"));
        try (var ignored = scope.enter(p.getId(), 3)) { assertEquals(1.08, UnitEquipmentEffects.mul(equipment.snapshot(p.getId()), "infantry", "defense"), 1e-9); }
        ArmyUnit army = createArmyUnit(p.getId(), "infantry", 50000); armyUnitRepository.delete(army);
        assertEquals("ue_infantry_helmet", equipment.loadouts(p.getId()).get("infantry").get("protection"));
        equipment.save(p.getId(), "infantry", Map.of(), loadouts.findByPlayerIdAndUnitType(p.getId(), "infantry").orElseThrow().getVersion() + 1);
        assertEquals(1, inventory(p.getId(), "ue_infantry_helmet"));
    }
    @Test void threeSlotSaveIsAtomicReturnsOldEquipmentAndRejectsStaleVersion() {
        Player p = createTestPlayer(); item(p.getId(), "ue_infantry_helmet"); item(p.getId(), "ue_infantry_vest"); item(p.getId(), "ue_infantry_radio");
        equipment.save(p.getId(), "infantry", Map.of("protection", "ue_infantry_helmet"), 0L);
        long v = loadouts.findByPlayerIdAndUnitType(p.getId(), "infantry").orElseThrow().getVersion() + 1;
        equipment.save(p.getId(), "infantry", Map.of("protection", "ue_infantry_vest", "support", "ue_infantry_radio"), v);
        assertEquals(1, inventory(p.getId(), "ue_infantry_helmet")); assertEquals(0, inventory(p.getId(), "ue_infantry_vest"));
        assertEquals(2, equipment.loadouts(p.getId()).get("infantry").size());
        assertThrows(IllegalArgumentException.class, () -> equipment.save(p.getId(), "infantry", Map.of(), v));
    }
    @Test void invalidUnitSlotAndUnownedItemCannotConsumeAnything() {
        Player p = createTestPlayer(); item(p.getId(), "ue_infantry_helmet");
        assertThrows(IllegalArgumentException.class, () -> equipment.save(p.getId(), "infantry", Map.of("protection", "ue_infantry_helmet", "support", "ue_infantry_lmg"), null));
        assertEquals(1, inventory(p.getId(), "ue_infantry_helmet"));
        assertTrue(loadouts.findByPlayerId(p.getId()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> equipment.save(p.getId(), "motor", Map.of(), null));
    }
    @Test void cityLaneManufacturesOnceAndRefundsOnlyFrozenCost() {
        Player p = createTestPlayer(); tech(p.getId(), 1); createBuilding(p.getId(), "factory", 2);
        giveResources(p.getId(), 100000, 100000, 100000, 100000, 100000);
        equipment.manufacture(p.getId(), "ue_infantry_helmet"); equipment.manufacture(p.getId(), "ue_infantry_lmg");
        var lane = orders.findByPlayerIdAndCitySlotOrderByStartAtAscIdAsc(p.getId(), 0);
        assertEquals(lane.get(0).getEndAt(), lane.get(1).getStartAt());
        equipment.cancel(p.getId(), lane.get(1).getId());
        assertEquals(99700, resourcesRepository.findByPlayerId(p.getId()).orElseThrow().getSteel());
        equipment.cancel(p.getId(), lane.get(0).getId());
        assertEquals(99850, resourcesRepository.findByPlayerId(p.getId()).orElseThrow().getSteel());
        equipment.manufacture(p.getId(), "ue_infantry_helmet");
        var order = orders.findByPlayerId(p.getId()).get(0); order.setEndAt(1L); orders.save(order);
        equipment.complete(p.getId(), System.currentTimeMillis()); equipment.complete(p.getId(), System.currentTimeMillis());
        assertEquals(1, inventory(p.getId(), "ue_infantry_helmet")); assertTrue(orders.findByPlayerId(p.getId()).isEmpty());
    }
    @Test void scoutEquipmentRequiresAirportInsteadOfFactory() {
        Player p = createTestPlayer(); tech(p.getId(), 1); createBuilding(p.getId(), "factory", 10);
        giveResources(p.getId(), 100000, 100000, 100000, 100000, 100000);
        assertThrows(IllegalArgumentException.class, () -> equipment.manufacture(p.getId(), "ue_scout_armor"));
        assertTrue(orders.findByPlayerId(p.getId()).isEmpty());
        assertEquals(100000, getResources(p.getId()).getSteel());
        assertEquals(0, ((Map<?, ?>) equipment.state(p.getId()).get("facilities")).get("scout"));
        createBuilding(p.getId(), "airport", 2);
        equipment.manufacture(p.getId(), "ue_scout_armor");
        assertEquals(1, orders.findByPlayerId(p.getId()).size());
        assertEquals(2, ((Map<?, ?>) equipment.state(p.getId()).get("facilities")).get("scout"));
    }
    @Test void alreadyManufacturedGearIsUsableWithoutTechnologyOrFacilities() {
        Player p = createTestPlayer(); item(p.getId(), "ue_htank_armor");
        equipment.save(p.getId(), "htank", Map.of("protection", "ue_htank_armor"), null);
        assertEquals(1.15, UnitEquipmentEffects.mul(equipment.snapshot(p.getId()), "htank", "defense"), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> equipment.manufacture(p.getId(), "ue_htank_armor"));
    }
    @Test void dispatchFreezesActionButNewBattleReadsCurrentConfiguration() {
        Player p = createTestPlayer(); giveResources(p.getId(), 100000, 100000, 100000, 100000, 100000);
        var world = createTestWorld(); world.setTerrainData("0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE)); worldMapRepository.save(world);
        createArmyUnit(p.getId(), "truck", 10); item(p.getId(), "ue_truck_cargo"); item(p.getId(), "ue_truck_economy");
        equipment.save(p.getId(), "truck", Map.of("combat", "ue_truck_cargo", "support", "ue_truck_economy"), null);
        Bandit b = new Bandit(); b.setWorldId(world.getId()); b.setName("目标"); b.setX(20); b.setY(10); b.setLevel(1); b.setArmy("{\"infantry\":1}"); b.setDefeated(false); banditRepository.save(b);
        var request = new DispatchRequest("bandit", b.getId(), "conquer", Map.of("truck", 1), null, Map.of());
        var preview = marchService.previewRoute(p.getId(), request);
        March m = marchService.createDispatch(p.getId(), request); long arrival = m.getArriveAt();
        assertEquals(600, marchService.calcArmyLoad(Map.of("truck", 1), JsonUtil.parseIntMap(m.getUnitEquipmentSnapshot())));
        assertEquals(600, preview.get("cargoLimit"));
        equipment.save(p.getId(), "truck", Map.of(), null);
        assertEquals(arrival, m.getArriveAt());
        assertEquals(.95, UnitEquipmentEffects.mul(JsonUtil.parseIntMap(m.getUnitEquipmentSnapshot()), "truck", "oil"), 1e-9);
        assertEquals(1, UnitEquipmentEffects.mul(targets.getCombatTechMap(p.getId()), "truck", "load"));
    }
    @Test void supportProfilesRemainOwnerCorrectAndLossesReturnToBaseInventory() {
        Player owner = createTestPlayer("gear-owner", 30), ally = createTestPlayer("gear-ally", 30);
        var world = createTestWorld();
        PlayerCity city = new PlayerCity(); city.setOwnerId(owner.getId()); city.setCitySlot(0); city.setWorldId(world.getId()); city.setName("守城"); city.setX(10); city.setY(10); city.setLevel(1); playerCityRepository.save(city);
        Guild guild = new Guild(); guild.setName("装备军团"); guild.setLeaderPlayerId(owner.getId()); guild.setCreatedAt(1L); guilds.save(guild);
        members.save(new GuildMember(null, guild.getId(), owner.getId(), "leader", 1L)); members.save(new GuildMember(null, guild.getId(), ally.getId(), "member", 1L));
        item(ally.getId(), "ue_infantry_vest"); equipment.save(ally.getId(), "infantry", Map.of("protection", "ue_infantry_vest"), null);
        March sent = new March(); sent.setPlayerId(ally.getId()); sent.setTargetKind("player"); sent.setTargetId(city.getId().toString()); sent.setSupportOwnerId(owner.getId()); sent.setArmy("{\"infantry\":10}"); sent.setStationed(true); sent.setAction("reinforce"); sent.setStartAt(0L); sent.setArriveAt(0L); marchRepository.save(sent);
        var defense = support.reconnaissance(city, Map.of("infantry", 10));
        String group = defense.army().keySet().stream().filter(id -> id.contains("~")).findFirst().orElseThrow();
        assertEquals(1, UnitEquipmentEffects.mul(defense.equipment(), "infantry", "hp"));
        assertEquals(1.12, UnitEquipmentEffects.mul(defense.equipment(), group, "hp"), 1e-9);
        var loss = support.settle(JsonUtil.toJson(defense.support()), defense.army(), Map.of("infantry", 10, group, 4));
        assertEquals(10, loss.survivors().get("infantry")); assertEquals(4, loss.support().get(0).survivors().get("infantry"));
        assertEquals(Map.of("infantry", 4), JsonUtil.parseIntMap(sent.getArmy()));
        assertEquals("ue_infantry_vest", equipment.loadouts(ally.getId()).get("infantry").get("protection"));
    }

    private WorldMap flatWorld() {
        WorldMap world = createTestWorld(); world.setTerrainData("0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE));
        return worldMapRepository.save(world);
    }

    @Test void arrivalReadsNewCombatGearButTacticalRoundsAndReportKeepOpeningSnapshot() {
        Player p = createTestPlayer(); giveResources(p.getId(), 100000, 100000, 100000, 100000, 100000);
        WorldMap world = flatWorld(); createArmyUnit(p.getId(), "infantry", 1000);
        WildTile tile = createWildTile(world.getId(), "forest", 20, 10, 1, Map.of("infantry", 1000), 0);
        March march = marchService.createDispatch(p.getId(), new DispatchRequest("wild", tile.getId(), "conquer", Map.of("infantry", 1000), null, null, null, "manual"));
        long arrival = march.getArriveAt(); assertTrue(JsonUtil.parseIntMap(march.getUnitEquipmentSnapshot()).isEmpty());
        item(p.getId(), "ue_infantry_helmet"); item(p.getId(), "ue_infantry_vest");
        equipment.save(p.getId(), "infantry", Map.of("protection", "ue_infantry_helmet"), null);
        marchService.processMarches(p.getId(), arrival);
        BattleSession session = battles.findByMarchId(march.getId()).orElseThrow();
        String frozen = session.getAttackerTech(); assertEquals(8, JsonUtil.parseIntMap(frozen).get("@ue:infantry:defense"));
        equipment.save(p.getId(), "infantry", Map.of("protection", "ue_infantry_vest"), null);
        marchService.processTimedOutTacticalBattle(session.getId(), arrival + 15000);
        assertEquals(frozen, battles.findById(session.getId()).orElseThrow().getAttackerTech());
        for (int round = 2; round <= 30 && battles.existsById(session.getId()); round++) marchService.processTimedOutTacticalBattle(session.getId(), arrival + round * 15000L);
        assertFalse(battles.existsById(session.getId()));
        String report = scoutReportRepository.findByPlayerId(p.getId()).get(0).getData();
        assertTrue(report.contains("@ue:infantry:item:ue_infantry_helmet"));
        assertFalse(report.contains("@ue:infantry:item:ue_infantry_vest"));
    }

    @Test void legacyGatherWithoutRoutesKeepsBaseCapacityDespiteNewEquipment() {
        Player p = createTestPlayer(); WorldMap world = flatWorld();
        WildTile tile = createWildTile(world.getId(), "ironworks", 15, 15, 1, Map.of(), 10000);
        tile.setOccupied(true); tile.setOccupiedBy(p.getId()); tile.setGarrison("{\"truck\":2}");
        tile.setGathering(true); tile.setGatherStartAt(0L); tile.setGatherEndAt(1L); tile.setGatherLoad(1000); tile.setGatherRes("steel"); wildTileRepository.save(tile);
        item(p.getId(), "ue_truck_cargo"); equipment.save(p.getId(), "truck", Map.of("combat", "ue_truck_cargo"), null);
        assertEquals(1000, marchService.harvestWild(p.getId(), tile.getId()).get("harvestAmount"));
        marchService.recallWild(p.getId(), tile.getId());
        March returning = marchRepository.findByPlayerId(p.getId()).get(0);
        assertTrue(JsonUtil.parseIntMap(returning.getUnitEquipmentSnapshot()).isEmpty());
        assertEquals(1000, returning.getGatherAmount());
    }

    @Test void gatherFreezesLoadAndCapsHarvestAfterGarrisonLosses() {
        Player p = createTestPlayer(); WorldMap world = flatWorld();
        WildTile tile = createWildTile(world.getId(), "ironworks", 15, 15, 1, Map.of(), 10000);
        tile.setOccupied(true); tile.setOccupiedBy(p.getId()); tile.setGarrison("{\"truck\":3}"); wildTileRepository.save(tile);
        item(p.getId(), "ue_truck_cargo"); equipment.save(p.getId(), "truck", Map.of("combat", "ue_truck_cargo"), null);
        assertEquals(1800, marchService.startWildGather(p.getId(), tile.getId()).get("gatherAmount"));
        tile.setGarrison("{\"truck\":1}"); tile.setGatherStartAt(0L); tile.setGatherEndAt(1L); wildTileRepository.save(tile);
        equipment.save(p.getId(), "truck", Map.of(), null);
        assertEquals(600, marchService.harvestWild(p.getId(), tile.getId()).get("harvestAmount"));
        marchService.recallWild(p.getId(), tile.getId());
        March returning = marchRepository.findByPlayerId(p.getId()).get(0);
        assertEquals(600, returning.getGatherAmount());
        assertEquals(20, JsonUtil.parseIntMap(returning.getUnitEquipmentSnapshot()).get("@ue:truck:load"));
    }

    @Test void manufactureRequiresKnowledgeAndCompletedFacilityAndPreviewRejectsChangedGear() {
        Player p = createTestPlayer(); WorldMap world = flatWorld();
        giveResources(p.getId(), 100000, 100000, 100000, 100000, 100000);
        Building facility = createBuilding(p.getId(), "factory", 1); tech(p.getId(), 1);
        assertThrows(IllegalArgumentException.class, () -> equipment.manufacture(p.getId(), "ue_infantry_helmet"));
        facility.setLevel(2); buildingRepository.save(facility);
        assertThrows(IllegalArgumentException.class, () -> equipment.manufacture(p.getId(), "ue_infantry_radio"));
        equipment.manufacture(p.getId(), "ue_infantry_helmet");
        createArmyUnit(p.getId(), "infantry", 10);
        WildTile tile = createWildTile(world.getId(), "forest", 15, 15, 1, Map.of(), 0);
        DispatchRequest request = new DispatchRequest("wild", tile.getId(), "conquer", Map.of("infantry", 10), null, null);
        String version = (String) marchService.previewRoute(p.getId(), request).get("unitEquipmentVersion");
        item(p.getId(), "ue_infantry_helmet"); equipment.save(p.getId(), "infantry", Map.of("protection", "ue_infantry_helmet"), null);
        var stale = new DispatchRequest("wild", tile.getId(), "conquer", Map.of("infantry", 10), null, null, null, null, null, version);
        assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(p.getId(), stale));
        assertEquals(10, armyUnitRepository.findByPlayerIdAndType(p.getId(), "infantry").get(0).getCount());
    }

    @Test void transportCargoEquipmentExpandsAirliftCapacityAndKeepsRemainingCargoLimit() {
        Player p = createTestPlayer(); WorldMap world = flatWorld();
        char[] mask = world.getTerrainData().toCharArray();
        for (int y = 0; y < WorldTerrainService.SIZE; y++) mask[y * WorldTerrainService.SIZE + 100] = '1';
        world.setTerrainData(new String(mask)); worldMapRepository.save(world);
        WildTile tile = createWildTile(world.getId(), "forest", 120, 10, 1, Map.of(), 0);
        assertThrows(IllegalArgumentException.class, () -> routes.plan(p.getId(), tile, Map.of("infantry", 801, "transport", 1), false));
        item(p.getId(), "ue_transport_cargo"); equipment.save(p.getId(), "transport", Map.of("combat", "ue_transport_cargo"), null);
        var lift = routes.plan(p.getId(), tile, Map.of("infantry", 801, "transport", 1), false);
        assertEquals("airlift", lift.mode()); assertEquals(159, lift.cargoLimit()); assertEquals(801, lift.reservedLoad());
    }
}
