package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SortieBattleTest extends BaseServiceTest {
    @Autowired private BattleSessionRepository sessions;

    private March attack(Player defender, int citySlot, Map<String, Integer> attackingArmy) {
        Player attacker = createTestPlayer("sortie-attacker", 30);
        long now = System.currentTimeMillis();
        attacker.setWarAgainstId(defender.getId());
        attacker.setWarAt(now - 60000L);
        attacker.setWarEndAt(now + 600000L);
        playerRepository.save(attacker);
        PlayerCity city = new PlayerCity();
        city.setWorldId(createTestWorld().getId());
        city.setOwnerId(defender.getId());
        city.setCitySlot(citySlot);
        city.setName("迎战测试城");
        city.setX(20);
        city.setY(20);
        city = playerCityRepository.save(city);
        March march = createMarch(attacker.getId(), "player", city.getId().toString(), city.getName(),
                10, 10, 20, 20, attackingArmy, "plunder", now - 60000L, now - 1L, false, false);
        marchService.processMarches(attacker.getId(), now);
        return marchRepository.findById(march.getId()).orElseThrow();
    }

    private Fortification fort(Long playerId, int citySlot, int count) {
        Fortification fort = new Fortification();
        fort.setPlayerId(playerId);
        fort.setCitySlot(citySlot);
        fort.setType("bunker");
        fort.setCount(count);
        return fortificationRepository.save(fort);
    }

    @Test
    void automaticSortieUsesAttackedCityCapacityAndDoesNotCountForts() {
        Player defender = createTestPlayer("sortie-defender", 30);
        createBuilding(defender.getId(), "wall", 10);
        ArmyUnit infantry = createArmyUnit(defender.getId(), "infantry", 100000);
        infantry.setCitySlot(1);
        armyUnitRepository.save(infantry);
        createArmyUnit(defender.getId(), "infantry", 500);
        fort(defender.getId(), 1, 1000);

        March march = attack(defender, 1, Map.of("infantry", 100));
        BattleSession session = sessions.findById(march.getBattleId()).orElseThrow();
        assertEquals(Map.of("infantry", 37500, "bunker", 1000), JsonUtil.parseIntMap(session.getInitialDefender()));
        assertEquals(100000, armyUnitRepository.findByPlayerIdAndCitySlotAndType(defender.getId(), 1, "infantry").get(0).getCount());
        assertEquals(500, armyUnitRepository.findByPlayerIdAndCitySlotAndType(defender.getId(), 0, "infantry").get(0).getCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10000})
    void partialSortiePreservesReservesAndNewTroopsOnVictoryAndDefeat(int attackers) {
        Player defender = createTestPlayer("partial-sortie", 30);
        defender.setSortieArmy(JsonUtil.toJson(Map.of("infantry", 100)));
        playerRepository.save(defender);
        ArmyUnit infantry = createArmyUnit(defender.getId(), "infantry", 100000);
        ArmyUnit rockets = createArmyUnit(defender.getId(), "rocket", 9000);
        March march = attack(defender, 0, Map.of("infantry", attackers));
        BattleSession session = sessions.findById(march.getBattleId()).orElseThrow();
        assertEquals(Map.of("infantry", 100), JsonUtil.parseIntMap(session.getInitialDefender()));
        // 战斗快照已生成，再修改战术和新增驻军；本场只应结算快照中的 100 名步兵。
        defender.setSortieArmy("{}");
        playerRepository.save(defender);
        infantry.setCount(100050);
        armyUnitRepository.save(infantry);
        Fortification newFort = fort(defender.getId(), 0, 5);
        long deadline = session.getRoundDeadlineAt();
        for (int round = 0; round < 30 && sessions.findById(session.getId()).isPresent(); round++) {
            marchService.processTimedOutTacticalBattle(session.getId(), deadline + round * 15000L);
        }
        assertTrue(sessions.findById(session.getId()).isEmpty());
        int remaining = armyUnitRepository.findById(infantry.getId()).orElseThrow().getCount();
        assertTrue(remaining >= 99950 && remaining <= 100050, "只允许扣除100名参战步兵的损失");
        assertEquals(9000, armyUnitRepository.findById(rockets.getId()).orElseThrow().getCount());
        assertEquals(5, fortificationRepository.findById(newFort.getId()).orElseThrow().getCount());
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(march.getPlayerId()).get(0).getData());
        assertEquals(attackers == 10000, report.get("win"));
    }

    @Test
    void zeroSortieLeavesOnlyFortsInBattle() {
        Player defender = createTestPlayer("zero-sortie", 30);
        defender.setSortieArmy("{}");
        playerRepository.save(defender);
        createArmyUnit(defender.getId(), "infantry", 100000);
        fort(defender.getId(), 0, 10);
        March march = attack(defender, 0, Map.of("infantry", 100));
        assertEquals(Map.of("bunker", 10), JsonUtil.parseIntMap(sessions.findById(march.getBattleId()).orElseThrow().getInitialDefender()));
    }

    @Test
    void incomingOfflineBattleAlsoHonorsManualSortieAndPreservesReserves() {
        Player defender = createTestPlayer("offline-sortie", 30);
        defender.setSortieArmy(JsonUtil.toJson(Map.of("infantry", 10)));
        playerRepository.save(defender);
        ArmyUnit infantry = createArmyUnit(defender.getId(), "infantry", 100000);
        ArmyUnit rockets = createArmyUnit(defender.getId(), "rocket", 50000);
        IncomingMarch incoming = new IncomingMarch();
        incoming.setTargetPlayerId(defender.getId());
        incoming.setArmy(JsonUtil.toJson(Map.of("htank", 10000)));
        incoming.setAction("plunder");
        incoming.setArriveAt(0L);
        incoming = incomingMarchRepository.save(incoming);
        marchService.processIncoming(defender.getId(), System.currentTimeMillis());
        assertFalse(incomingMarchRepository.existsById(incoming.getId()));
        assertEquals(99990, armyUnitRepository.findById(infantry.getId()).orElseThrow().getCount());
        assertEquals(50000, armyUnitRepository.findById(rockets.getId()).orElseThrow().getCount());
    }

    @Test
    void attackAllowsExactly25000AndRejects25001BeforeDeductingTroops() {
        Player attacker = createTestPlayer("capacity-boundary", 30);
        giveResources(attacker.getId(), 10000000, 0, 0, 0, 0);
        ArmyUnit army = createArmyUnit(attacker.getId(), "infantry", 100000);
        WildTile target = createWildTile(createTestWorld().getId(), "forest", 11, 10, 1, Map.of(), 0);
        assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(attacker.getId(),
                new DispatchRequest("wild", target.getId(), "conquer", Map.of("infantry", 25001), null, null)));
        assertEquals(100000, armyUnitRepository.findById(army.getId()).orElseThrow().getCount());
        March march = marchService.createDispatch(attacker.getId(),
                new DispatchRequest("wild", target.getId(), "conquer", Map.of("infantry", 25000), null, null));
        assertEquals(Map.of("infantry", 25000), JsonUtil.parseIntMap(march.getArmy()));
        assertEquals(75000, armyUnitRepository.findById(army.getId()).orElseThrow().getCount());
    }
}
