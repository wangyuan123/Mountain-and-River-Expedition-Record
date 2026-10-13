package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GuildSupportTest extends BaseServiceTest {
    @Autowired GuildMemberRepository members;
    @Autowired GuildRepository guilds;
    @Autowired GuildRelationRepository relations;
    @Autowired GuildSupportService support;
    @Autowired CityScope scope;
    @Autowired BattleSessionRepository battles;
    @Autowired MarchTargetService targets;
    @Autowired WoundedUnitRepository wounded;
    private Player sender, receiver, outsider;
    private PlayerCity source, destination, foreignCity;
    private Guild guild;
    private WorldMap world;

    @BeforeEach void setup() {
        world = createTestWorld();
        world.setTerrainData("0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE));
        worldMapRepository.save(world);
        sender = createTestPlayer("support-sender", 30);
        receiver = createTestPlayer("support-receiver", 30);
        outsider = createTestPlayer("support-outsider", 30);
        source = city(sender, 10, 0); destination = city(receiver, 20, 0); foreignCity = city(outsider, 30, 0);
        guild = new Guild(); guild.setName("support-guild"); guild.setLeaderPlayerId(sender.getId()); guild.setCreatedAt(1L);
        guilds.save(guild);
        members.save(new GuildMember(null, guild.getId(), sender.getId(), "leader", 1L));
        members.save(new GuildMember(null, guild.getId(), receiver.getId(), "member", 1L));
        giveResources(sender.getId(), 100000, 100000, 100000, 100000, 100000);
        createArmyUnit(sender.getId(), "infantry", 200);
        createArmyUnit(sender.getId(), "truck", 20);
        createArmyUnit(receiver.getId(), "infantry", 10);
    }

    private PlayerCity city(Player owner, int x, int slot) {
        PlayerCity city = new PlayerCity(); city.setOwnerId(owner.getId()); city.setCitySlot(slot); city.setName("城" + x);
        city.setWorldId(world.getId()); city.setX(x); city.setY(10); city.setLevel(1);
        playerCityRepository.save(city);
        if (slot == 0) {
            owner.setCityPosX(x); owner.setCityPosY(10); owner.setActiveCityId(city.getId()); playerRepository.save(owner);
        } else {
            Resources stock = new Resources(); stock.setPlayerId(owner.getId()); stock.setCitySlot(slot); stock.setFood(0); resourcesRepository.save(stock);
        }
        return city;
    }

    private DispatchRequest request(PlayerCity city, String action, Map<String, Integer> army, Map<String, Integer> cargo) {
        return new DispatchRequest("player", city.getId(), action, army, null, cargo);
    }

    private March dispatch(String action, Map<String, Integer> army, Map<String, Integer> cargo) {
        return marchService.createDispatch(sender.getId(), request(destination, action, army, cargo));
    }

    private void arrive(March march) {
        try (var ignored = scope.enter(sender.getId(), march.getCitySlot())) {
            marchService.processMarches(sender.getId(), march.getArriveAt() + 1);
        }
    }

    private int troop(Long playerId, int slot, String unit) {
        return armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, slot, unit).stream().mapToInt(ArmyUnit::getCount).sum();
    }

    @Test void transportDeliversOnceAndReturnsToOriginalOwner() {
        March march = dispatch("transport", Map.of("truck", 2), Map.of("food", 80, "gold", 20));
        assertFalse(marchService.previewRoute(sender.getId(), request(destination, "transport", Map.of("truck", 2), Map.of())).isEmpty());
        arrive(march);
        Resources stock = resourcesRepository.findByPlayerIdAndCitySlot(receiver.getId(), 0).orElseThrow();
        assertEquals(1080, stock.getFood()); assertEquals(520, stock.getGold());
        assertTrue(march.getReturning()); assertEquals(0, troop(receiver.getId(), 0, "truck"));
        arrive(march);
        assertEquals(20, troop(sender.getId(), 0, "truck")); assertEquals(1080, stock.getFood());
        assertFalse(marchRepository.existsById(march.getId()));
    }

    @Test void receiverBranchGetsCargoWithoutChangingMainCity() {
        destination = city(receiver, 22, 1);
        March march = dispatch("transport", Map.of("truck", 2), Map.of("food", 100));
        arrive(march);
        assertEquals(100, resourcesRepository.findByPlayerIdAndCitySlot(receiver.getId(), 1).orElseThrow().getFood());
        assertEquals(1000, resourcesRepository.findByPlayerIdAndCitySlot(receiver.getId(), 0).orElseThrow().getFood());
        assertEquals(0, scope.slot(receiver.getId()));
    }

    @Test void supportRejectsOutsidersCrossGuildFriendsAndRebaseBeforeDeductingAssets() {
        Guild other = new Guild(); other.setName("other-guild"); other.setLeaderPlayerId(outsider.getId()); other.setCreatedAt(1L); guilds.save(other);
        members.save(new GuildMember(null, other.getId(), outsider.getId(), "leader", 1L));
        GuildRelation relation = new GuildRelation(); relation.setGuildLowId(Math.min(guild.getId(), other.getId()));
        relation.setGuildHighId(Math.max(guild.getId(), other.getId())); relation.setStatus("friendly"); relation.setUpdatedByPlayerId(sender.getId()); relation.setUpdatedAt(1L); relations.save(relation);
        for (String action : List.of("transport", "reinforce")) {
            DispatchRequest request = request(foreignCity, action, Map.of("truck", 2), Map.of("food", 10));
            assertThrows(IllegalArgumentException.class, () -> marchService.previewRoute(sender.getId(), request));
            assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(sender.getId(), request));
        }
        assertThrows(IllegalArgumentException.class, () -> dispatch("rebase", Map.of("infantry", 10), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> support.memberCities(sender.getId(), outsider.getId()));
        assertEquals(20, troop(sender.getId(), 0, "truck"));
        assertEquals(100000, resourcesRepository.findByPlayerId(sender.getId()).orElseThrow().getFood());
    }

    @Test void membershipChangeInTransitReturnsUndeliveredCargoInFull() {
        March march = dispatch("transport", Map.of("truck", 2), Map.of("food", 100));
        members.delete(members.findByPlayerId(receiver.getId()).orElseThrow()); members.flush();
        arrive(march); assertTrue(march.getReturning()); assertEquals(100, JsonUtil.parseIntMap(march.getCarryRes()).get("food"));
        arrive(march);
        assertEquals(100000, resourcesRepository.findByPlayerId(sender.getId()).orElseThrow().getFood());
        assertEquals(1000, resourcesRepository.findByPlayerId(receiver.getId()).orElseThrow().getFood());
    }

    @Test void cityChangingToAnotherGuildmateStillCancelsShipment() {
        March march = dispatch("transport", Map.of("truck", 2), Map.of("food", 100));
        members.save(new GuildMember(null, guild.getId(), outsider.getId(), "member", 1L));
        destination.setOwnerId(outsider.getId()); destination.setCitySlot(1); playerCityRepository.save(destination);
        arrive(march); assertTrue(march.getReturning()); assertFalse(march.getStationed());
        assertEquals(100, JsonUtil.parseIntMap(march.getCarryRes()).get("food"));
    }

    @Test void reinforcementStaysSeparateCanBeRecalledAndCannotOpenAnAttack() {
        March march = dispatch("reinforce", Map.of("infantry", 100), Map.of());
        long duration = march.getArriveAt() - march.getStartAt();
        arrive(march); arrive(march);
        assertTrue(march.getStationed()); assertEquals(100, troop(sender.getId(), 0, "infantry"));
        assertEquals(10, troop(receiver.getId(), 0, "infantry"));
        assertEquals(1, support.received(receiver.getId()).size());
        assertThrows(IllegalArgumentException.class, () -> marchService.getTacticalBattle(sender.getId(), march.getId()));
        marchService.cancelMarch(sender.getId(), march.getId());
        assertFalse(march.getStationed()); assertTrue(march.getReturning());
        assertEquals(duration, march.getArriveAt() - march.getStartAt());
        arrive(march); assertEquals(200, troop(sender.getId(), 0, "infantry"));
        assertEquals(10, troop(receiver.getId(), 0, "infantry"));
    }

    @Test void onlyCurrentReceivingCityOwnerMayDismiss() {
        March march = dispatch("reinforce", Map.of("infantry", 100), Map.of()); arrive(march);
        assertThrows(IllegalArgumentException.class, () -> marchService.dismissSupport(outsider.getId(), march.getId()));
        PlayerCity branch = city(receiver, 24, 1);
        try (var ignored = scope.enter(branch)) {
            assertThrows(IllegalArgumentException.class, () -> marchService.dismissSupport(receiver.getId(), march.getId()));
        }
        marchService.dismissSupport(receiver.getId(), march.getId()); arrive(march);
        assertEquals(200, troop(sender.getId(), 0, "infantry"));
    }

    @Test void leavingGuildAfterArrivalAutomaticallyReturnsGarrison() {
        March march = dispatch("reinforce", Map.of("infantry", 100), Map.of()); arrive(march);
        members.delete(members.findByPlayerId(receiver.getId()).orElseThrow()); members.flush();
        marchService.processMarches(sender.getId(), System.currentTimeMillis());
        assertTrue(march.getReturning()); assertFalse(march.getStationed()); arrive(march);
        assertEquals(200, troop(sender.getId(), 0, "infantry"));
    }

    @Test void battleSnapshotSharesLossesAndPreservesLateReinforcements() {
        March first = dispatch("reinforce", Map.of("infantry", 100), Map.of()); arrive(first);
        GuildSupportService.Defense defense;
        try (var ignored = scope.enter(destination)) {
            defense = support.defense(destination, targets.getTargetArmy(destination));
        }
        assertEquals(110, defense.army().get("infantry"));
        March late = dispatch("reinforce", Map.of("infantry", 50), Map.of()); arrive(late);
        GuildSupportService.Losses losses = support.settle(JsonUtil.toJson(defense.support()), defense.army(), Map.of("infantry", 55));
        assertEquals(5, losses.initial().get("infantry") - losses.survivors().get("infantry"));
        assertEquals(50, JsonUtil.parseIntMap(first.getArmy()).get("infantry"));
        assertEquals(50, JsonUtil.parseIntMap(late.getArmy()).get("infantry"));
        marchService.cancelMarch(sender.getId(), first.getId()); arrive(first);
        assertEquals(100, troop(sender.getId(), 0, "infantry"));
    }

    @Test void fullBattleUsesGarrisonSnapshotBlocksRecallAndReturnsOnlySurvivors() {
        outsider.setWarAgainstId(receiver.getId()); outsider.setWarAt(1L); outsider.setWarEndAt(System.currentTimeMillis() + 3600000); playerRepository.save(outsider);
        March reinforcement = dispatch("reinforce", Map.of("infantry", 100), Map.of()); arrive(reinforcement);
        createTechnology(sender.getId(), "log_medical", 10);
        createArmyUnit(outsider.getId(), "htank", 1000);
        giveResources(outsider.getId(), 100000, 100000, 100000, 100000, 100000);
        March attack = marchService.createDispatch(outsider.getId(), request(destination, "plunder", Map.of("htank", 1000), Map.of()));
        marchService.processMarches(outsider.getId(), attack.getArriveAt() + 1);
        BattleSession session = battles.findByMarchId(attack.getId()).orElseThrow();
        assertEquals(110, JsonUtil.parseIntMap(session.getInitialDefender()).get("infantry"));
        assertEquals(100, JsonUtil.parseIntMap(JsonUtil.toJson(JsonUtil.parseObjMap(session.getDefenderSupport()).get(reinforcement.getId().toString()))).get("infantry"));
        assertThrows(IllegalArgumentException.class, () -> marchService.cancelMarch(sender.getId(), reinforcement.getId()));
        assertThrows(IllegalArgumentException.class, () -> marchService.dismissSupport(receiver.getId(), reinforcement.getId()));
        for (int i = 0; i < 30 && battles.existsById(session.getId()); i++) marchService.executeTacticalRound(outsider.getId(), attack.getId(), null);
        assertFalse(battles.existsById(session.getId()));
        var battleReport = scoutReportRepository.findByPlayerId(receiver.getId()).stream()
                .filter(report -> "battle".equals(report.getType())).findFirst().orElseThrow();
        var data = JsonUtil.parseObjMap(battleReport.getData());
        var initial = JsonUtil.parseIntMap(JsonUtil.toJson(data.get("initialDefender")));
        var surviving = JsonUtil.parseIntMap(JsonUtil.toJson(data.get("survivorDefender")));
        assertEquals(initial.get("infantry") - surviving.getOrDefault("infantry", 0), ((Number) data.get("losses")).intValue());
        int stationed = marchRepository.findById(reinforcement.getId()).map(m -> JsonUtil.parseIntMap(m.getArmy()).getOrDefault("infantry", 0)).orElse(0);
        assertTrue(stationed < 100);
        assertTrue(troop(receiver.getId(), 0, "infantry") <= 10);
        var injured = wounded.findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(sender.getId(), 0, System.currentTimeMillis());
        assertFalse(injured.isEmpty());
        assertTrue(injured.stream().allMatch(batch -> batch.getCityId().equals(source.getId())));
        if (stationed > 0) { marchService.cancelMarch(sender.getId(), reinforcement.getId()); arrive(reinforcement); }
        assertEquals(100 + stationed, troop(sender.getId(), 0, "infantry"));
    }

    @Test void reinforcementFromBranchReturnsToThatBranchAfterSwitching() {
        PlayerCity branch = city(sender, 12, 1);
        ArmyUnit unit = new ArmyUnit(); unit.setPlayerId(sender.getId()); unit.setCitySlot(1); unit.setType("infantry"); unit.setCount(20); armyUnitRepository.save(unit);
        Resources stock = resourcesRepository.findByPlayerIdAndCitySlot(sender.getId(), 1).orElseThrow(); stock.setFood(10000); stock.setOil(10000); resourcesRepository.save(stock);
        March march;
        try (var ignored = scope.enter(branch)) {
            march = dispatch("reinforce", Map.of("infantry", 20), Map.of()); arrive(march);
        }
        marchService.dismissSupport(receiver.getId(), march.getId()); arrive(march);
        assertEquals(20, troop(sender.getId(), 1, "infantry")); assertEquals(200, troop(sender.getId(), 0, "infantry"));
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void reconnaissanceIncludesSupportAndScoutLossesBelongToSenders(String unit) {
        createArmyUnit(sender.getId(), unit, 20);
        createArmyUnit(receiver.getId(), unit, 10);
        createTechnology(sender.getId(), "log_medical", 10);
        createTechnology(receiver.getId(), "log_medical", 10);
        March reinforcement = dispatch("reinforce", Map.of(unit, 20, "infantry", 100), Map.of()); arrive(reinforcement);
        // 精确情报统计全部驻军，即使城主把战术迎战编队设为空。
        receiver.setSortieArmy("{}"); playerRepository.save(receiver);
        createTechnology(outsider.getId(), "recon_level", 2);
        createArmyUnit(outsider.getId(), unit, 1000);
        giveResources(outsider.getId(), 100000, 100000, 100000, 100000, 100000);
        March scouting = marchService.createDispatch(outsider.getId(), request(destination, "scout", Map.of(unit, 1000), Map.of()));
        marchService.processMarches(outsider.getId(), scouting.getArriveAt() + 1);
        var data = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(outsider.getId()).get(0).getData());
        assertEquals(unit, data.get("scoutUnit"));
        assertEquals(30, ((Number) data.get("enemyScouts")).intValue());
        assertEquals(30, ((Number) data.get("enemyLost")).intValue());
        assertEquals(110, JsonUtil.parseIntMap(JsonUtil.toJson(data.get("army"))).get("infantry"));
        assertEquals(0, troop(receiver.getId(), 0, unit));
        assertEquals(Map.of("infantry", 100), JsonUtil.parseIntMap(reinforcement.getArmy()));
        var senderWounded = wounded.findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(sender.getId(), 0, System.currentTimeMillis());
        assertEquals(10, senderWounded.stream().filter(batch -> unit.equals(batch.getType())).mapToInt(WoundedUnit::getCount).sum());
        assertTrue(senderWounded.stream().allMatch(batch -> source.getId().equals(batch.getCityId())));
        var receiverWounded = wounded.findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(receiver.getId(), 0, System.currentTimeMillis());
        assertEquals(5, receiverWounded.stream().filter(batch -> unit.equals(batch.getType())).mapToInt(WoundedUnit::getCount).sum());
        assertTrue(receiverWounded.stream().allMatch(batch -> destination.getId().equals(batch.getCityId())));
        var defense = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(receiver.getId()).get(0).getData());
        assertEquals("defender", defense.get("perspective"));
        assertEquals(Map.of(unit, 30), JsonUtil.parseIntMap(JsonUtil.toJson(defense.get("myArmy"))));
        assertEquals(Map.of(unit, 1000), JsonUtil.parseIntMap(JsonUtil.toJson(defense.get("enemyArmy"))));
        assertEquals(Map.of(unit, 5), JsonUtil.parseIntMap(JsonUtil.toJson(defense.get("wounded"))));
        assertFalse(((List<?>) defense.get("combatLog")).isEmpty());
        for (String hidden : List.of("army", "resources", "buildings", "forts", "techs", "officers", "defensePower")) {
            assertFalse(defense.containsKey(hidden), "守方报告不能复制城市情报字段 " + hidden);
        }
        // 重复抵达不重复扣兵、生成报告或伤兵。
        marchService.processMarches(outsider.getId(), scouting.getStartAt());
        assertEquals(1, scoutReportRepository.findByPlayerId(receiver.getId()).size());
        assertEquals(senderWounded.size(), wounded.findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(sender.getId(), 0, System.currentTimeMillis()).size());
    }

    @Test void groundReconSupportWoundedRemainInOriginalBranch() {
        PlayerCity branch = city(sender, 12, 1);
        ArmyUnit cars = new ArmyUnit(); cars.setPlayerId(sender.getId()); cars.setCitySlot(1); cars.setType("recon_car"); cars.setCount(20); armyUnitRepository.save(cars);
        Resources stock = resourcesRepository.findByPlayerIdAndCitySlot(sender.getId(), 1).orElseThrow(); stock.setFood(10000); stock.setOil(10000); resourcesRepository.save(stock);
        createArmyUnit(sender.getId(), "recon_car", 7);
        createArmyUnit(receiver.getId(), "recon_car", 10);
        createArmyUnit(outsider.getId(), "recon_car", 1000);
        createTechnology(sender.getId(), "log_medical", 10);
        giveResources(outsider.getId(), 100000, 100000, 100000, 100000, 100000);
        March reinforcement;
        try (var ignored = scope.enter(branch)) {
            reinforcement = dispatch("reinforce", Map.of("recon_car", 20), Map.of()); arrive(reinforcement);
        }
        March scouting = marchService.createDispatch(outsider.getId(), request(destination, "scout", Map.of("recon_car", 1000), Map.of()));
        marchService.processMarches(outsider.getId(), scouting.getArriveAt() + 1);
        assertEquals(7, troop(sender.getId(), 0, "recon_car"));
        assertEquals(0, troop(sender.getId(), 1, "recon_car"));
        var injured = wounded.findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(sender.getId(), 1, System.currentTimeMillis());
        assertEquals(10, injured.stream().mapToInt(WoundedUnit::getCount).sum());
        assertTrue(injured.stream().allMatch(batch -> branch.getId().equals(batch.getCityId()) && "recon_car".equals(batch.getType())));
        assertTrue(wounded.findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(sender.getId(), 0, System.currentTimeMillis()).isEmpty());
    }

    @Test void reconCarsRebaseAndReturnFromWildToTheOriginalBranch() {
        PlayerCity branch = city(sender, 12, 1);
        createArmyUnit(sender.getId(), "recon_car", 30);
        March relocation = marchService.createDispatch(sender.getId(), request(branch, "rebase", Map.of("recon_car", 10), Map.of()));
        arrive(relocation);
        assertEquals(20, troop(sender.getId(), 0, "recon_car")); assertEquals(10, troop(sender.getId(), 1, "recon_car"));
        Resources stock = resourcesRepository.findByPlayerIdAndCitySlot(sender.getId(), 1).orElseThrow(); stock.setFood(10000); stock.setOil(10000); resourcesRepository.save(stock);
        WildTile wild = createWildTile(world.getId(), "oil", 14, 10, 1, Map.of(), 10000);
        wild.setOccupied(true); wild.setOccupiedBy(sender.getId()); wildTileRepository.save(wild);
        March station;
        try (var ignored = scope.enter(branch)) {
            station = marchService.createDispatch(sender.getId(), new DispatchRequest("wild", wild.getId(), "station", Map.of("recon_car", 10), null, null));
            arrive(station);
        }
        assertFalse(marchRepository.existsById(station.getId()));
        assertEquals(10, JsonUtil.parseIntMap(wild.getGarrison()).get("recon_car"));
        marchService.recallWild(sender.getId(), wild.getId());
        March returning = marchRepository.findByPlayerId(sender.getId()).stream()
                .filter(march -> String.valueOf(wild.getId()).equals(march.getTargetId())).findFirst().orElseThrow();
        assertEquals(1, returning.getCitySlot()); arrive(returning);
        assertEquals(20, troop(sender.getId(), 0, "recon_car")); assertEquals(10, troop(sender.getId(), 1, "recon_car"));
    }

    @Test void invalidGarrisonWaitsForBattleBeforeAutomaticallyReturning() {
        March reinforcement = dispatch("reinforce", Map.of("infantry", 100), Map.of()); arrive(reinforcement);
        createArmyUnit(outsider.getId(), "infantry", 10);
        giveResources(outsider.getId(), 100000, 100000, 100000, 100000, 100000);
        outsider.setWarAgainstId(receiver.getId()); outsider.setWarAt(1L); outsider.setWarEndAt(System.currentTimeMillis() + 3600000); playerRepository.save(outsider);
        March attack = marchService.createDispatch(outsider.getId(), request(destination, "plunder", Map.of("infantry", 10), Map.of()));
        marchService.processMarches(outsider.getId(), attack.getArriveAt() + 1);
        BattleSession battle = battles.findByMarchId(attack.getId()).orElseThrow();
        members.delete(members.findByPlayerId(receiver.getId()).orElseThrow()); members.flush();
        marchService.processMarches(sender.getId(), System.currentTimeMillis());
        assertTrue(reinforcement.getStationed()); assertFalse(reinforcement.getReturning());
        for (int i = 0; i < 30 && battles.existsById(battle.getId()); i++) marchService.executeTacticalRound(outsider.getId(), attack.getId(), null);
        assertFalse(battles.existsById(battle.getId()));
        marchService.processMarches(sender.getId(), System.currentTimeMillis());
        March returning = marchRepository.findById(reinforcement.getId()).orElseThrow();
        assertFalse(returning.getStationed()); assertTrue(returning.getReturning());
    }
}
