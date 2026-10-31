package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.CityScope;
import com.wargame.service.CityService;
import com.wargame.service.WoundedService;
import com.wargame.service.CityConquestService;
import com.wargame.util.JsonUtil;
import com.wargame.util.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经真实战术会话验证接管、俘虏和失守后数据隔离，包含离线调度结算路径。 */
@AutoConfigureMockMvc
class PlayerCityConquestTest extends BaseServiceTest {
    @Autowired EntityManager em;
    @Autowired CityScope scope;
    @Autowired CityService cities;
    @Autowired OfficerEquipmentRepository equipment;
    @Autowired WoundedUnitRepository wounded;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired WoundedService woundedService;
    @Autowired MarketOrderRepository marketOrders;
    @Autowired MockMvc http;
    @Autowired JwtUtil jwt;
    @org.springframework.boot.test.mock.mockito.SpyBean CityConquestService conquest;
    @Autowired PlayerPowerSnapshotRepository powerSnapshots;
    private Player attacker;
    private Player defender;
    private WorldMap world;

    @BeforeEach void setup() {
        world = createTestWorld();
        attacker = createTestPlayer("city-attacker", 30); attacker.setMilitaryRank(4);
        defender = createTestPlayer("city-defender", 30);
        city(attacker, 0, 10, 10, 70);
        city(defender, 0, 20, 20, 70);
        long now = System.currentTimeMillis();
        attacker.setWarAgainstId(defender.getId()); attacker.setWarAt(now - 1); attacker.setWarEndAt(now + 3_600_000);

        PlayerPowerSnapshot snap1 = new PlayerPowerSnapshot();
        snap1.setPlayerId(attacker.getId());
        snap1.setObservedAt(now);
        snap1.setFormationPower(500_000L);
        snap1.setOverallPower(500_000L);
        powerSnapshots.save(snap1);

        PlayerPowerSnapshot snap2 = new PlayerPowerSnapshot();
        snap2.setPlayerId(defender.getId());
        snap2.setObservedAt(now);
        snap2.setFormationPower(500_000L);
        snap2.setOverallPower(500_000L);
        powerSnapshots.save(snap2);

        em.flush();
    }

    private PlayerCity city(Player owner, int slot, int x, int y, int morale) {
        PlayerCity city = new PlayerCity(); city.setWorldId(world.getId()); city.setOwnerId(owner.getId()); city.setCitySlot(slot);
        city.setName("城" + slot); city.setX(x); city.setY(y); city.setLevel(3); city.setMorale(morale);
        city.setLastTick(System.currentTimeMillis()); city.setCivilianPopulation(125);
        playerCityRepository.save(city);
        if (slot == 0) {
            owner.setCityName(city.getName()); owner.setCityPosX(x); owner.setCityPosY(y); owner.setPosX(x); owner.setPosY(y);
            owner.setActiveCityId(city.getId()); owner.setMorale(morale);
        } else {
            Resources r = new Resources(); r.setPlayerId(owner.getId()); r.setCitySlot(slot);
            r.setFood(1234); r.setSteel(2345); r.setOil(3456); r.setRare(456); r.setGold(789); r.setDiamond(0); resourcesRepository.save(r);
        }
        building(owner.getId(), slot, "command", 3);
        CityState peace = new CityState(); peace.setPlayerId(owner.getId()); peace.setCitySlot(slot); peace.setStatus("peace"); cityStateRepository.save(peace);
        return city;
    }

    // IDENTITY保存即插入，必须提前写好城市槽位与MySQL非空字段，不能保存后再补。
    private Building building(Long playerId, int slot, String type, int level) {
        Building b = new Building(); b.setPlayerId(playerId); b.setCitySlot(slot); b.setType(type); b.setLevel(level);
        return buildingRepository.save(b);
    }

    private ArmyUnit army(Long playerId, int slot, String type, int count) {
        ArmyUnit unit = new ArmyUnit(); unit.setPlayerId(playerId); unit.setCitySlot(slot); unit.setType(type); unit.setCount(count);
        return armyUnitRepository.save(unit);
    }

    private Officer officer(Long playerId, int slot, String role, int military, int defense, int logistics, int knowledge) {
        Officer o = new Officer(); o.setPlayerId(playerId); o.setCitySlot(slot); o.setName("TestOfficer");
        o.setRole(role); o.setMilitary(military); o.setDefense(defense); o.setLogistics(logistics); o.setKnowledge(knowledge);
        o.setStar(1); o.setLevel(1); o.setLoyalty(80); o.setSalary(0); o.setExp(0L); o.setRecruitAt(0L); o.setRewardAt(0L);
        return officerRepository.save(o);
    }

    private Officer commander(int slot, int loyalty) {
        Officer o = officer(defender.getId(), slot, "commander", 15, 12, 13, 14);
        o.setLoyalty(loyalty); o.setName("守将"); o.setStar(5); o.setLevel(42);
        o.setExp(123456L); o.setAttrPoints(9); o.setSkills("[{\"id\":\"mil_boost\",\"lv\":3}]");
        o.setBio("完整履历"); o.setSalary(12); o.setRewardAt(987L); o.setRecruitAt(456L); o.setNameRenamedAt(321L);
        return o;
    }

    private OfficerEquipment equip(Officer o) {
        OfficerEquipment e = new OfficerEquipment(); e.setPlayerId(defender.getId()); e.setOfficerId(o.getId());
        e.setItemKey("test-military-weapon"); e.setSetType("military"); e.setTier(3); e.setSlot("weapon");
        e.setMilitaryBonus(20); e.setDefenseBonus(8); e.setLogisticsBonus(4); e.setKnowledgeBonus(5);
        e.setCreatedAt(123L); e.setEquippedAt(234L); return equipment.save(e);
    }

    private March begin(PlayerCity target, String action, int attackCount, int defendCount) {
        if (defendCount > 0) army(defender.getId(), target.getCitySlot(), "infantry", defendCount);
        long now = System.currentTimeMillis();
        March m = createMarch(attacker.getId(), "player", target.getId().toString(), target.getName(), 10, 10,
                target.getX(), target.getY(), Map.of("infantry", attackCount), action, now - 60_000, now - 1, false, false);
        marchService.processMarches(attacker.getId(), now);
        assertNotNull(marchRepository.findById(m.getId()).orElseThrow().getBattleId());
        return m;
    }

    private void finish(Long marchId) {
        long now = System.currentTimeMillis();
        for (int round = 0; round < 30; round++) {
            March m = marchRepository.findById(marchId).orElse(null);
            if (m == null || m.getBattleId() == null) return;
            now += 15_000;
            marchService.processTimedOutTacticalBattle(m.getBattleId(), now);
        }
        fail("战斗未完成");
    }

    private Map<String, Object> report(Long playerId) {
        return JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData());
    }

    /** 战斗期间正常结算经济；接管须保留接管前账本，而不是回退到开战时库存。 */
    private Map<Long, List<Long>> balancesAtTakeover() {
        Map<Long, List<Long>> balances = new HashMap<>();
        org.mockito.Mockito.doAnswer(call -> {
            PlayerCity target = call.getArgument(1);
            marchService.settleSupportFood(target, call.getArgument(2));
            for (Resources stock : resourcesRepository.findAll())
                if (defender.getId().equals(stock.getPlayerId()))
                    balances.put(stock.getId(), List.of(stock.getFood(), stock.getGold()));
            return call.callRealMethod();
        }).when(conquest).takeOver(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(PlayerCity.class), org.mockito.ArgumentMatchers.anyLong());
        return balances;
    }

    @Test void takeoverAndCapturePreserveBuildingsStockAndCompleteOfficerButClearMilitaryAssets() {
        PlayerCity target = city(defender, 1, 30, 30, 2);
        target.setResentment(12);
        Long targetId = target.getId();
        Building farm = building(defender.getId(), 1, "farm", 7); Long farmId = farm.getId();
        Construction build = createConstruction(defender.getId(), "farm", 8, 1, System.currentTimeMillis() + 3_600_000, 0); build.setCitySlot(1);
        Long buildId = build.getId();
        Officer o = commander(1, 0); Long captiveId = o.getId();
        OfficerEquipment worn = equip(o); Long wornId = worn.getId();
        Officer removed = officer(defender.getId(), 1, "mayor", 1, 30, 1, 1); Long removedId = removed.getId();
        Long removedGear = equip(removed).getId();
        Officer homeCommander = officer(attacker.getId(), 0, "commander", 30, 30, 30, 30);
        Long homeCommanderId = homeCommander.getId();
        army(defender.getId(), 1, "truck", 11);
        ArmyUnit safe = army(defender.getId(), 0, "rocket", 17); Long safeId = safe.getId();
        ArmyProductionQueue q = new ArmyProductionQueue(); q.setPlayerId(defender.getId()); q.setCitySlot(1); q.setUnitType("infantry");
        q.setUnitCount(10); q.setStartedAt(1L); q.setFinishesAt(System.currentTimeMillis() + 3_600_000); q.setDurationSeconds(3600); q.setSpeedMultiplier(1.0); queues.save(q);
        Long queueId = q.getId();
        March lostMarch = createMarch(defender.getId(), "wild", "9999", "在途军队", 30, 30, 40, 40,
                Map.of("truck", 5), "gather", 1, System.currentTimeMillis() + 3_600_000, false, false);
        lostMarch.setCitySlot(1); Long lostMarchId = lostMarch.getId();
        var balances = balancesAtTakeover();
        Long originalStockId = resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 1).orElseThrow().getId();
        March attack = begin(target, "conquer", 1000, 1); Long attackId = attack.getId();
        finish(attackId);

        PlayerCity owned = playerCityRepository.findById(targetId).orElseThrow();
        assertEquals(attacker.getId(), owned.getOwnerId()); assertEquals(1, owned.getCitySlot()); assertEquals(0, owned.getMorale());
        assertEquals(13, owned.getResentment(), "分城须先结算败战民怨，再随城权转移保留");
        assertEquals(1, report(defender.getId()).get("resentmentChange"));
        assertEquals(13, report(attacker.getId()).get("enemyResentmentAfter"));
        assertEquals(attacker.getId(), buildingRepository.findById(farmId).orElseThrow().getPlayerId());
        assertEquals(7, buildingRepository.findById(farmId).orElseThrow().getLevel());
        assertEquals(attacker.getId(), constructionRepository.findById(buildId).orElseThrow().getPlayerId());
        Resources stock = resourcesRepository.findByPlayerIdAndCitySlot(attacker.getId(), 1).orElseThrow();
        assertEquals(originalStockId, stock.getId());
        assertEquals(balances.get(stock.getId()), List.of(stock.getFood(), stock.getGold()));
        assertTrue(JsonUtil.parseIntMap(marchRepository.findById(attackId).orElseThrow().getCarryRes()).values().stream().allMatch(n -> n == 0));
        Officer captive = officerRepository.findById(captiveId).orElseThrow();
        assertEquals(attacker.getId(), captive.getPlayerId()); assertEquals("idle", captive.getRole()); assertEquals(0, captive.getCitySlot());
        assertEquals(0, captive.getLoyalty()); assertEquals(42, captive.getLevel()); assertEquals(5, captive.getStar());
        assertEquals(123456L, captive.getExp()); assertEquals(9, captive.getAttrPoints()); assertEquals("完整履历", captive.getBio());
        assertEquals("[{\"id\":\"mil_boost\",\"lv\":3}]", captive.getSkills());
        assertEquals(15, captive.getMilitary()); assertEquals(12, captive.getDefense()); assertEquals(13, captive.getLogistics()); assertEquals(14, captive.getKnowledge());
        assertEquals(12, captive.getSalary()); assertEquals(987L, captive.getRewardAt()); assertEquals(456L, captive.getRecruitAt()); assertEquals(321L, captive.getNameRenamedAt());
        OfficerEquipment gear = equipment.findById(wornId).orElseThrow();
        assertEquals(attacker.getId(), gear.getPlayerId()); assertEquals(captiveId, gear.getOfficerId()); assertEquals(20, gear.getMilitaryBonus());
        assertEquals(3, gear.getTier()); assertEquals(123L, gear.getCreatedAt()); assertEquals(234L, gear.getEquippedAt());
        assertEquals("commander", officerRepository.findById(homeCommanderId).orElseThrow().getRole());
        assertFalse(officerRepository.existsById(removedId)); assertFalse(equipment.existsById(removedGear)); assertFalse(queues.existsById(queueId));
        assertFalse(marchRepository.existsById(lostMarchId)); assertTrue(armyUnitRepository.findByPlayerIdAndCitySlot(defender.getId(), 1).isEmpty());
        assertTrue(armyUnitRepository.findByPlayerIdAndCitySlot(attacker.getId(), 1).isEmpty()); assertTrue(armyUnitRepository.existsById(safeId));
        assertEquals(true, report(attacker.getId()).get("cityConquered")); assertEquals(captiveId.intValue(), report(attacker.getId()).get("capturedOfficerId"));
        assertEquals(true, report(defender.getId()).get("cityConquered")); assertEquals(false, report(defender.getId()).get("win"));
        assertEquals(2, cities.overview(attacker.getId()).get("count"));
        cities.select(attacker.getId(), targetId);
        assertEquals(stock.getFood(), ((Map<?, ?>) gameStateService.getGameState(attacker.getId()).get("resources")).get("food"));
    }

    @Test void insufficientRankPreventsTakeoverButStillCapturesZeroLoyaltyCommander() {
        attacker.setMilitaryRank(1);
        PlayerCity target = city(defender, 1, 30, 30, 0); Long targetId = target.getId();
        Officer o = commander(1, 0); Long officerId = o.getId();
        March march = begin(target, "conquer", 1000, 1); finish(march.getId());
        assertEquals(defender.getId(), playerCityRepository.findById(targetId).orElseThrow().getOwnerId());
        assertEquals(attacker.getId(), officerRepository.findById(officerId).orElseThrow().getPlayerId());
        assertEquals(false, report(attacker.getId()).get("cityConquered"));
        assertTrue(report(attacker.getId()).get("cityTakeoverReason").toString().contains("军衔上限"));
        assertEquals(0, resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 1).orElseThrow().getFood(),
                "步兵自携负重足够时，未接管城市也可搬走普通资源");
    }

    @Test void peacefulCityTransferMovesOfficersAndLeavesOtherCityOfficersWithOwner() {
        PlayerCity target = city(defender, 1, 30, 30, 70);
        Officer commander = commander(1, 80);
        Officer mayor = officer(defender.getId(), 1, "mayor", 1, 2, 3, 4);
        Officer retained = officer(defender.getId(), 0, "idle", 1, 2, 3, 4);

        conquest.peacefulTransfer(attacker.getId(), target, System.currentTimeMillis());
        em.flush(); em.clear();

        assertEquals(attacker.getId(), officerRepository.findById(commander.getId()).orElseThrow().getPlayerId());
        assertEquals(attacker.getId(), officerRepository.findById(mayor.getId()).orElseThrow().getPlayerId());
        assertEquals(defender.getId(), officerRepository.findById(retained.getId()).orElseThrow().getPlayerId());

    }

    @ParameterizedTest
    @CsvSource({"2,2", "3,3", "4,4", "5,4", "6,5", "7,5", "8,6", "9,6", "10,6",
            "11,7", "12,7", "13,7", "14,7", "15,8", "16,8", "17,8"})
    void constructionCitiesCountAgainstRankCapAndPreventTakeover(int tier, int cap) {
        attacker.setMilitaryRank(tier);
        for (int slot = 1; slot < cap; slot++) {
            PlayerCity reserved = city(attacker, slot, 12 + 3 * slot, 12, 70);
            reserved.setReadyAt(System.currentTimeMillis() + 3_600_000);
        }
        PlayerCity target = city(defender, 1, 30, 30, 0); Long targetId = target.getId();
        March march = begin(target, "conquer", 1000, 1); finish(march.getId());
        assertEquals(defender.getId(), playerCityRepository.findById(targetId).orElseThrow().getOwnerId());
        assertEquals(false, report(attacker.getId()).get("cityConquered"));
    }

    @Test void positiveMoraleAndLoyaltyKeepCityAndCommanderWithDefender() {
        PlayerCity target = city(defender, 1, 30, 30, 3); Long targetId = target.getId();
        Officer o = commander(1, 1); Long officerId = o.getId();
        March march = begin(target, "conquer", 1000, 1); finish(march.getId());
        assertEquals(1, playerCityRepository.findById(targetId).orElseThrow().getMorale());
        assertEquals(defender.getId(), officerRepository.findById(officerId).orElseThrow().getPlayerId());
        assertNull(report(attacker.getId()).get("capturedOfficerId"));
        assertFalse(report(attacker.getId()).get("report").toString().contains("俘虏"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"conquer,0", "plunder,0", "conquer,1", "plunder,1"})
    void cityLootMatchesSurvivorCapacityReportsAndActualDefenderDeduction(String action, int truckCount) {
        PlayerCity target = city(defender, 1, 30, 30, 70);
        var before = resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 1).orElseThrow();
        before.setFood(20_000); before.setSteel(20_000); before.setOil(20_000); before.setRare(20_000); before.setGold(20_000);
        var initial = Map.of("food", before.getFood(), "steel", before.getSteel(), "oil", before.getOil(),
                "rare", before.getRare(), "gold", before.getGold());
        long now = System.currentTimeMillis();
        March march = createMarch(attacker.getId(), "player", target.getId().toString(), target.getName(),
                10, 10, 30, 30, Map.of("infantry", 1000, "truck", truckCount), action, now - 60_000, now - 1, false, false);
        march.setCarryRes(JsonUtil.toJson(Map.of("food", 100)));
        marchService.processMarches(attacker.getId(), now);
        finish(march.getId());

        March returning = marchRepository.findById(march.getId()).orElseThrow();
        var loot = JsonUtil.parseIntMap(JsonUtil.toJson(report(attacker.getId()).get("plunder")));
        var cargo = JsonUtil.parseIntMap(returning.getCarryRes());
        int capacity = marchService.calcArmyLoad(JsonUtil.parseIntMap(returning.getArmy()));
        assertEquals(10_000 + 500 * truckCount, capacity);
        assertEquals(capacity - 100, loot.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(capacity, cargo.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(report(attacker.getId()).get("plunder"), report(defender.getId()).get("plunder"));
        var after = resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 1).orElseThrow();
        var stock = Map.of("food", after.getFood(), "steel", after.getSteel(), "oil", after.getOil(),
                "rare", after.getRare(), "gold", after.getGold());
        initial.forEach((key, amount) -> {
            assertEquals(amount - loot.getOrDefault(key, 0), stock.get(key), key);
            assertEquals(loot.getOrDefault(key, 0) + ("food".equals(key) ? 100 : 0), cargo.getOrDefault(key, 0), key);
        });
        if ("plunder".equals(action)) assertEquals(0, loot.get("gold"));
        assertEquals(defender.getId(), playerCityRepository.findById(target.getId()).orElseThrow().getOwnerId());
    }

    @Test void plunderNeverTakesOverOrCapturesEvenAtZeroMoraleAndLoyalty() {
        PlayerCity target = city(defender, 1, 30, 30, 0); Long targetId = target.getId();
        Long officerId = commander(1, 0).getId();
        March march = begin(target, "plunder", 1000, 1); finish(march.getId());
        assertEquals(defender.getId(), playerCityRepository.findById(targetId).orElseThrow().getOwnerId());
        assertEquals(defender.getId(), officerRepository.findById(officerId).orElseThrow().getPlayerId());
        assertNull(report(attacker.getId()).get("capturedOfficerId")); assertEquals(false, report(attacker.getId()).get("cityConquered"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"feint", "harass", "guerrilla"})
    void harassmentVictoryDoesNotLootTakeOverOrCapture(String action) {
        PlayerCity target = city(defender, 1, 30, 30, 0);
        Long officerId = commander(1, 0).getId();
        var before = resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 1).orElseThrow();
        var stock = java.util.List.of(before.getFood(), before.getSteel(), before.getOil(), before.getRare(), before.getGold());
        March march = begin(target, action, 1000, 0);
        finish(march.getId());
        var result = report(attacker.getId());
        assertEquals(true, result.get("attackerWin"));
        assertEquals(false, result.get("cityConquered"));
        assertNull(result.get("capturedOfficerId"));
        assertTrue(((Map<?, ?>) result.get("plunder")).values().stream().allMatch(amount -> ((Number) amount).intValue() == 0));
        assertEquals(defender.getId(), playerCityRepository.findById(target.getId()).orElseThrow().getOwnerId());
        assertEquals(defender.getId(), officerRepository.findById(officerId).orElseThrow().getPlayerId());
        var after = resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 1).orElseThrow();
        assertEquals(stock, java.util.List.of(after.getFood(), after.getSteel(), after.getOil(), after.getRare(), after.getGold()));
    }

    @ParameterizedTest
    @CsvSource({"1,conquer,100,0,3", "1,conquer,71,0,3", "1,conquer,70,0,2",
            "1,conquer,41,0,2", "1,conquer,40,0,1", "1,conquer,0,0,1",
            "1,plunder,100,0,2", "1,plunder,71,0,2", "1,plunder,70,0,0",
            "1,plunder,41,0,0", "1,plunder,40,0,0", "1,plunder,0,0,0",
            "1,feint,100,7,0", "1,harass,100,7,0", "1,guerrilla,100,7,0",
            "1,conquer,100,98,2", "1,conquer,100,99,1", "1,conquer,100,100,0",
            "1,plunder,100,99,1", "1,plunder,100,100,0", "0,conquer,71,0,3",
            "0,conquer,41,9,2", "0,plunder,71,0,2", "0,conquer,100,99,1"})
    void defeatedCityResentmentUsesPreBattleMoraleAndActualCity(int slot, String action, int moraleBefore,
                                                               int resentmentBefore, int actualGain) {
        // 无接管名额，让 0 民心档位也能直接核对原守城方的持久化经济数据。
        attacker.setMilitaryRank(1);
        attacker.setResentment(11);
        defender.setResentment(17);
        PlayerCity target = slot == 0
                ? playerCityRepository.findByOwnerIdAndCitySlot(defender.getId(), 0).orElseThrow()
                : city(defender, slot, 30, 30, moraleBefore);
        CityEconomy economy = slot == 0 ? defender : target;
        economy.setMorale(moraleBefore);
        economy.setResentment(resentmentBefore);
        if (slot == 0) {
            // 主城地图记录是冗余值，不能作为民心定档或民怨累加的依据。
            target.setMorale(10);
            target.setResentment(44);
        }
        PlayerCity selected = city(defender, 2, 40, 40, 22);
        selected.setResentment(29);
        defender.setActiveCityId(selected.getId());
        Long targetId = target.getId();
        Long selectedId = selected.getId();
        Long attackerId = attacker.getId();
        Long defenderId = defender.getId();

        March march = begin(target, action, 1000, 0);
        Long battleId = march.getBattleId();
        finish(march.getId());
        marchService.processTimedOutTacticalBattle(battleId, System.currentTimeMillis() + 3_600_000L);
        em.flush();
        em.clear();

        PlayerCity saved = playerCityRepository.findById(targetId).orElseThrow();
        CityEconomy savedEconomy = slot == 0 ? playerRepository.findById(defenderId).orElseThrow() : saved;
        assertEquals(defenderId, saved.getOwnerId());
        assertEquals(resentmentBefore + actualGain, savedEconomy.getResentment());
        assertEquals(Math.max(0, moraleBefore - 2), savedEconomy.getMorale());
        assertEquals(11, playerRepository.findById(attackerId).orElseThrow().getResentment());
        assertEquals(29, playerCityRepository.findById(selectedId).orElseThrow().getResentment());
        if (slot != 0) assertEquals(17, playerRepository.findById(defenderId).orElseThrow().getResentment());
        assertEquals(1, scoutReportRepository.findByPlayerId(defenderId).size(), "同场重复调度不重复生成战报");

        // 后续安抚或切城引发的经济变化不能改写已保存的本次战果。
        savedEconomy.setResentment(1);
        savedEconomy.setMorale(1);
        Map<String, Object> attackReport = report(attackerId);
        Map<String, Object> defenseReport = report(defenderId);
        assertEquals(true, attackReport.get("attackerWin"));
        assertEquals(actualGain, defenseReport.get("resentmentChange"));
        assertEquals(resentmentBefore + actualGain, defenseReport.get("resentmentAfter"));
        assertEquals(actualGain, attackReport.get("enemyResentmentChange"));
        assertEquals(resentmentBefore + actualGain, attackReport.get("enemyResentmentAfter"));
        assertEquals(0, attackReport.get("resentmentChange"));
        assertEquals(11, attackReport.get("resentmentAfter"));
        assertEquals(0, defenseReport.get("enemyResentmentChange"));
        assertEquals(11, defenseReport.get("enemyResentmentAfter"));
    }

    @Test void defeatNeverTakesOverOrCaptures() {
        attacker.setResentment(7);
        PlayerCity target = city(defender, 1, 30, 30, 0); Long targetId = target.getId(); Long officerId = commander(1, 0).getId();
        target.setResentment(13);
        March march = begin(target, "conquer", 1, 1000); finish(march.getId());
        assertEquals(false, report(attacker.getId()).get("win")); assertEquals(false, report(attacker.getId()).get("cityConquered"));
        assertEquals(defender.getId(), playerCityRepository.findById(targetId).orElseThrow().getOwnerId());
        assertEquals(defender.getId(), officerRepository.findById(officerId).orElseThrow().getPlayerId());
        assertEquals(7, attacker.getResentment(), "进攻失败不增加出发城民怨");
        assertEquals(13, target.getResentment(), "守城胜利不增加民怨");
        assertEquals(0, report(attacker.getId()).get("resentmentChange"));
        assertEquals(0, report(defender.getId()).get("resentmentChange"));
    }

    @Test void mainCityLossPromotesRemainingCityAndPreservesAccountWalletAndOtherAssets() {
        PlayerCity target = playerCityRepository.findByOwnerIdAndCitySlot(defender.getId(), 0).orElseThrow(); Long targetId = target.getId();
        defender.setMorale(2); target.setMorale(99);
        defender.setResentment(4); target.setResentment(44);
        Resources wallet = resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).orElseThrow(); wallet.setDiamond(999);
        PlayerCity remaining = city(defender, 2, 40, 40, 65); Long remainingId = remaining.getId();
        WildTile remainingWild = createWildTile(world.getId(), "forest", 50, 50, 1, Map.of(), 0);
        remainingWild.setOccupied(true); remainingWild.setOccupiedBy(defender.getId()); remainingWild.setOccupiedCitySlot(2);
        Long remainingWildId = remainingWild.getId();
        Officer safe = commander(2, 90); Long safeId = safe.getId();
        army(defender.getId(), 2, "rocket", 5);
        createTechnology(defender.getId(), "log_food", 6);
        var balances = balancesAtTakeover();
        Long remainingStockId = resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 2).orElseThrow().getId();
        March march = begin(target, "conquer", 1000, 1); finish(march.getId());
        Player player = playerRepository.findById(defender.getId()).orElseThrow();
        assertEquals(remainingId, player.getActiveCityId()); assertEquals(40, player.getCityPosX()); assertEquals(65, player.getMorale());
        assertEquals(0, player.getResentment(), "提升后的新主城保留自身民怨");
        assertEquals(5, playerCityRepository.findById(targetId).orElseThrow().getResentment(), "失守主城的民怨随城保留");
        assertEquals(1, report(defender.getId()).get("resentmentChange"));
        assertEquals(5, report(defender.getId()).get("resentmentAfter"));
        assertEquals(5, report(attacker.getId()).get("enemyResentmentAfter"));
        assertEquals(0, playerCityRepository.findById(remainingId).orElseThrow().getCitySlot());
        assertEquals(0, wildTileRepository.findById(remainingWildId).orElseThrow().getOccupiedCitySlot());
        assertEquals(defender.getId(), wildTileRepository.findById(remainingWildId).orElseThrow().getOccupiedBy());
        assertEquals(0, officerRepository.findById(safeId).orElseThrow().getCitySlot());
        assertEquals(999, resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).orElseThrow().getDiamond());
        assertEquals(0, resourcesRepository.findByPlayerIdAndCitySlot(attacker.getId(), 1).orElseThrow().getDiamond());
        Resources promotedStock = resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).orElseThrow();
        assertEquals(remainingStockId, promotedStock.getId());
        assertEquals(balances.get(remainingStockId), List.of(promotedStock.getFood(), promotedStock.getGold()));
        assertEquals(5, armyUnitRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).get(0).getCount());
        assertEquals(6, technologyRepository.findByPlayerId(defender.getId()).get(0).getLevel());
        assertEquals(attacker.getId(), playerCityRepository.findById(targetId).orElseThrow().getOwnerId());
        assertNotNull(gameStateService.getGameState(defender.getId()).get("cityOverview"));
        assertEquals(-2, report(attacker.getId()).get("enemyMoraleChange"));
        assertEquals(0, report(attacker.getId()).get("enemyMoraleAfter"));
        assertEquals(0, report(defender.getId()).get("moraleAfter"));
    }

    @Test void lastCityLossCreatesBasicMainCityWithoutRestoringLostMilitaryOrResettingAccount() {
        defender.setMorale(0); defender.setPrestige(1234); defender.setMilitaryRank(7);
        PlayerCity target = playerCityRepository.findByOwnerIdAndCitySlot(defender.getId(), 0).orElseThrow(); Long targetId = target.getId();
        resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).orElseThrow().setDiamond(777);
        Long officerId = commander(0, 80).getId();
        createTechnology(defender.getId(), "log_food", 4);
        March march = begin(target, "conquer", 1000, 1); finish(march.getId());
        PlayerCity replacement = playerCityRepository.findByOwnerIdAndCitySlot(defender.getId(), 0).orElseThrow();
        assertNotEquals(targetId, replacement.getId()); assertFalse(replacement.getX() == 20 && replacement.getY() == 20);
        assertEquals(1, replacement.getLevel()); assertEquals(4, buildingRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).size());
        assertTrue(armyUnitRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).isEmpty()); assertFalse(officerRepository.existsById(officerId));
        assertEquals(777, resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).orElseThrow().getDiamond());
        assertEquals(7, playerRepository.findById(defender.getId()).orElseThrow().getMilitaryRank());
        assertEquals(4, technologyRepository.findByPlayerId(defender.getId()).get(0).getLevel());
        assertEquals(replacement.getId(), playerRepository.findById(defender.getId()).orElseThrow().getActiveCityId());
        assertEquals(attacker.getId(), playerCityRepository.findById(targetId).orElseThrow().getOwnerId());
        assertNotNull(gameStateService.getGameState(defender.getId()).get("resources"));
    }

    @Test void onlyActualBattleCommanderCanBeCapturedAfterAppointmentsChange() {
        PlayerCity target = city(defender, 1, 30, 30, 50);
        Officer actual = commander(1, 0); Long actualId = actual.getId();
        Officer other = officer(defender.getId(), 1, "idle", 1, 30, 1, 1); other.setLoyalty(0); Long otherId = other.getId();
        March march = begin(target, "conquer", 1000, 1);
        actual.setRole("idle"); other.setRole("commander"); em.flush(); finish(march.getId());
        assertEquals(attacker.getId(), officerRepository.findById(actualId).orElseThrow().getPlayerId());
        assertEquals(defender.getId(), officerRepository.findById(otherId).orElseThrow().getPlayerId());
    }

    @Test void commanderAppointedAfterBattleStartedIsNotCaptured() {
        PlayerCity target = city(defender, 1, 30, 30, 50);
        March march = begin(target, "conquer", 1000, 1);
        Long officerId = commander(1, 0).getId(); em.flush(); finish(march.getId());
        assertEquals(defender.getId(), officerRepository.findById(officerId).orElseThrow().getPlayerId());
        assertNull(report(attacker.getId()).get("capturedOfficerId"));
    }

    @Test void cityConquestTransfersItsWildsAndEvacuatesOtherCitySurvivors() {
        PlayerCity target = city(defender, 1, 30, 30, 0);
        WildTile tile = createWildTile(world.getId(), "forest", 50, 50, 1, Map.of("infantry", 7), 1000);
        tile.setOccupied(true); tile.setOccupiedBy(defender.getId()); tile.setOccupiedCitySlot(1);
        tile.setGarrisonRoutes(JsonUtil.toJson(List.of(
                Map.of("playerId", defender.getId(), "citySlot", 0, "fromX", 20, "fromY", 20, "distance", 60,
                        "duration", 1000, "army", Map.of("infantry", 5)),
                Map.of("playerId", defender.getId(), "citySlot", 1, "fromX", 30, "fromY", 30, "distance", 40,
                        "duration", 1000, "army", Map.of("infantry", 5)))));
        Long tileId = tile.getId(); em.flush();
        March attack = begin(target, "conquer", 1000, 1); finish(attack.getId());
        WildTile transferred = wildTileRepository.findById(tileId).orElseThrow();
        assertEquals(attacker.getId(), transferred.getOccupiedBy()); assertEquals(1, transferred.getOccupiedCitySlot());
        assertEquals("{}", transferred.getGarrison()); assertNull(transferred.getGarrisonRoutes());
        assertEquals(1L, wildTileRepository.countByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(attacker.getId(), 1));
        assertEquals(0L, wildTileRepository.countByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(defender.getId(), 1));
        March returning = marchRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).stream()
                .filter(m -> tileId.toString().equals(m.getTargetId())).findFirst().orElseThrow();
        assertTrue(returning.getReturning()); assertEquals(5, JsonUtil.parseIntMap(returning.getArmy()).get("infantry"));
    }

    @Test void conquestRemovesWoundedAndOnlyLostCityGarrisonsAndReturnsMarketEscrowToCapturedStock() {
        PlayerCity target = city(defender, 1, 30, 30, 0); Long targetId = target.getId();
        createTechnology(defender.getId(), "log_medical", 10);
        woundedService.recordLosses(defender.getId(), 30, 30, Map.of("infantry", 10), Map.of(), null, System.currentTimeMillis());
        Long woundId = wounded.findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(defender.getId(), 1, System.currentTimeMillis()).get(0).getId();
        WildTile tile = createWildTile(world.getId(), "forest", 50, 50, 1, Map.of("infantry", 7), 1000);
        tile.setOccupied(true); tile.setOccupiedBy(defender.getId()); tile.setGatherCitySlot(1); tile.setGathering(true); tile.setGatherHarvested(10);
        tile.setGarrisonRoutes(JsonUtil.toJson(List.of(
                Map.of("playerId", defender.getId(), "citySlot", 0, "army", Map.of("infantry", 5)),
                Map.of("playerId", defender.getId(), "citySlot", 1, "army", Map.of("infantry", 5)))));
        Long tileId = tile.getId();
        MarketOrder order = new MarketOrder(); order.setSellerId(defender.getId()); order.setSellerName("守方"); order.setSellerCitySlot(1);
        order.setResourceType("food"); order.setAmount(100); order.setPricePerUnit(1); order.setTotalPrice(100L); marketOrders.save(order); Long orderId = order.getId();
        March march = begin(target, "conquer", 1000, 1); Long attackId = march.getId(); Long sessionId = marchRepository.findById(attackId).orElseThrow().getBattleId();
        finish(attackId);
        assertFalse(wounded.existsById(woundId));
        WildTile after = wildTileRepository.findById(tileId).orElseThrow();
        assertEquals(5, JsonUtil.parseIntMap(after.getGarrison()).get("infantry"));
        assertEquals(1, JsonUtil.parseList(after.getGarrisonRoutes()).size()); assertFalse(after.getGathering()); assertNull(after.getGatherHarvested());
        assertEquals(defender.getId(), after.getOccupiedBy());
        assertEquals("CANCELLED", marketOrders.findById(orderId).orElseThrow().getStatus());
        assertEquals(1334, resourcesRepository.findByPlayerIdAndCitySlot(attacker.getId(), 1).orElseThrow().getFood());
        assertEquals(attacker.getId(), playerCityRepository.findById(targetId).orElseThrow().getOwnerId());
        int reports = scoutReportRepository.findByPlayerId(attacker.getId()).size();
        marchService.processTimedOutTacticalBattle(sessionId, System.currentTimeMillis() + 600_000);
        assertEquals(reports, scoutReportRepository.findByPlayerId(attacker.getId()).size());
        assertEquals(2, cities.overview(attacker.getId()).get("count"));
    }

    @Test void captureGoesToTheMarchOriginCityAndLosingSelectedBranchFallsBackToMain() {
        attacker.setMilitaryRank(7);
        PlayerCity origin = city(attacker, 2, 12, 12, 70); Long originId = origin.getId();
        PlayerCity target = city(defender, 1, 30, 30, 0); Long targetId = target.getId();
        defender.setActiveCityId(targetId);
        Long officerId = commander(1, 0).getId();
        army(defender.getId(), 1, "infantry", 1);
        long now = System.currentTimeMillis();
        March m = createMarch(attacker.getId(), "player", targetId.toString(), target.getName(), 12, 12, 30, 30,
                Map.of("infantry", 1000), "conquer", now - 60_000, now - 1, false, false); m.setCitySlot(2); Long marchId = m.getId();
        try (var ignored = scope.enter(attacker.getId(), 2)) { marchService.processMarches(attacker.getId(), now); }
        finish(marchId);
        assertEquals(2, officerRepository.findById(officerId).orElseThrow().getCitySlot());
        assertEquals(3, playerCityRepository.findById(targetId).orElseThrow().getCitySlot());
        assertEquals(playerCityRepository.findByOwnerIdAndCitySlot(defender.getId(), 0).orElseThrow().getId(),
                playerRepository.findById(defender.getId()).orElseThrow().getActiveCityId());
        cities.select(attacker.getId(), originId);
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) gameStateService.getGameState(attacker.getId()).get("officers");
        assertTrue(list.stream().anyMatch(o -> ((Number) o.get("id")).longValue() == officerId));
    }

    @Test void waitingAttackCannotOpenAnotherBattleAgainstNewlyCapturedOwnCity() {
        PlayerCity target = city(defender, 1, 30, 30, 0);
        March first = begin(target, "conquer", 1000, 1); Long firstId = first.getId();
        long now = System.currentTimeMillis();
        March waiting = createMarch(attacker.getId(), "player", target.getId().toString(), target.getName(), 10, 10, 30, 30,
                Map.of("infantry", 100), "conquer", now - 60_000, now - 1, false, false);
        Long waitingId = waiting.getId();
        marchService.processMarches(attacker.getId(), now);
        assertNull(marchRepository.findById(waitingId).orElseThrow().getBattleId());
        finish(firstId);
        Map<String, Object> cancelled = marchService.getTacticalBattle(attacker.getId(), waitingId);
        assertEquals(true, cancelled.get("cancelled"));
        assertEquals("war_eligibility", cancelled.get("reason"));
        assertFalse(marchRepository.existsById(waitingId));
        assertEquals(100, armyUnitRepository.findByPlayerIdAndCitySlotAndType(attacker.getId(), 0, "infantry").get(0).getCount());
        assertEquals(1, scoutReportRepository.findByPlayerId(attacker.getId()).size());
    }

    @Test void transportAndRebaseFromOtherCitiesReturnWhenTheirDestinationIsLost() {
        PlayerCity target = city(defender, 1, 30, 30, 0);
        long now = System.currentTimeMillis();
        List<Long> incomingIds = new ArrayList<>();
        for (String action : List.of("transport", "rebase")) {
            March incoming = createMarch(defender.getId(), "player", target.getId().toString(), target.getName(), 20, 20, 30, 30,
                    Map.of("truck", 5), action, now, now + 600_000, false, false);
            incoming.setCarryRes(JsonUtil.toJson(Map.of("food", 100))); incomingIds.add(incoming.getId());
        }
        March attack = begin(target, "conquer", 1000, 1); finish(attack.getId());
        marchService.processMarches(defender.getId(), now + 600_001);
        for (Long id : incomingIds) {
            March returning = marchRepository.findById(id).orElseThrow();
            assertTrue(returning.getReturning()); assertEquals(100, JsonUtil.parseIntMap(returning.getCarryRes()).get("food"));
        }
        marchService.processMarches(defender.getId(), now + 1_200_002);
        for (Long id : incomingIds) assertFalse(marchRepository.existsById(id));
        assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(defender.getId(), 0, "truck").get(0).getCount());
        assertEquals(1200, resourcesRepository.findByPlayerIdAndCitySlot(defender.getId(), 0).orElseThrow().getFood());
        assertEquals(1234, resourcesRepository.findByPlayerIdAndCitySlot(attacker.getId(), 1).orElseThrow().getFood());
    }

    @Test void originalOwnerCannotAccessLostCityOrCapturedOfficerThroughHttp() throws Exception {
        PlayerCity target = city(defender, 1, 30, 30, 0); Long cityId = target.getId();
        Long officerId = commander(1, 0).getId();
        March attack = begin(target, "conquer", 1000, 1); finish(attack.getId());
        Player original = playerRepository.findById(defender.getId()).orElseThrow();
        Player conqueror = playerRepository.findById(attacker.getId()).orElseThrow();
        String oldAuth = "Bearer " + jwt.generateToken(original.getUsername(), original.getId(), original.getAuthVersion());
        String newAuth = "Bearer " + jwt.generateToken(conqueror.getUsername(), conqueror.getId(), conqueror.getAuthVersion());
        http.perform(get("/api/game/state").header("Authorization", oldAuth).header("X-City-Id", cityId))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/game/state").header("Authorization", newAuth).header("X-City-Id", cityId))
                .andExpect(status().isOk());
        http.perform(post("/api/game/officer/appoint").header("Authorization", oldAuth).contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtil.toJson(Map.of("officerId", officerId, "role", "commander"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));
        http.perform(post("/api/game/officer/appoint").header("Authorization", newAuth).contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtil.toJson(Map.of("officerId", officerId, "role", "commander"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }
}
