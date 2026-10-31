package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.*;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.PlayerPowerSnapshotRepository;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SortieBattleTest extends BaseServiceTest {
    @Autowired private BattleSessionRepository sessions;
    @Autowired private com.wargame.service.MobilizationService mobilization;
    @Autowired private com.wargame.service.BattleActionPreferences preferences;
    @Autowired private com.wargame.repository.PlayerItemRepository items;
    @Autowired private PlayerPowerSnapshotRepository powerSnapshots;

    private void balancePower(Player attacker, Player defender) {
        long now = System.currentTimeMillis();
        PlayerPowerSnapshot s1 = new PlayerPowerSnapshot();
        s1.setPlayerId(attacker.getId());
        s1.setObservedAt(now);
        s1.setFormationPower(5_000_000L);
        s1.setOverallPower(5_000_000L);
        powerSnapshots.save(s1);

        PlayerPowerSnapshot s2 = new PlayerPowerSnapshot();
        s2.setPlayerId(defender.getId());
        s2.setObservedAt(now);
        s2.setFormationPower(5_000_000L);
        s2.setOverallPower(5_000_000L);
        powerSnapshots.save(s2);
    }

    private March attack(Player defender, int citySlot, Map<String, Integer> attackingArmy) {
        Player attacker = createTestPlayer("sortie-attacker", 30);
        balancePower(attacker, defender);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void millionTroopsDefendOnlyAttackedCityRegardlessOfLegacyPreset(boolean legacyPreset) {
        Player defender = createTestPlayer("sortie-defender", 30);
        if (legacyPreset) {
            // 历史低额预设不再裁剪本城兵力，分城全员参战且不借用主城部队。
            defender.setSortieArmy(JsonUtil.toJson(Map.of("infantry", 37500)));
            playerRepository.save(defender);
        }
        createBuilding(defender.getId(), "wall", 10);
        ArmyUnit infantry = createArmyUnit(defender.getId(), "infantry", 1000000);
        infantry.setCitySlot(1);
        armyUnitRepository.save(infantry);
        createArmyUnit(defender.getId(), "infantry", 500);
        fort(defender.getId(), 1, 1000);

        March march = attack(defender, 1, Map.of("infantry", 100));
        BattleSession session = sessions.findById(march.getBattleId()).orElseThrow();
        assertEquals(Map.of("infantry", 1000000, "bunker", 1000), JsonUtil.parseIntMap(session.getInitialDefender()));
        assertEquals(1000000, armyUnitRepository.findByPlayerIdAndCitySlotAndType(defender.getId(), 1, "infantry").get(0).getCount());
        assertEquals(500, armyUnitRepository.findByPlayerIdAndCitySlotAndType(defender.getId(), 0, "infantry").get(0).getCount());
    }

    @Test
    void mobilizationExpiryDoesNotChangeFullDefenseOrRunningSnapshot() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        try (var ignored = com.wargame.util.GameClock.use(clock::get)) {
            Player defender = createTestPlayer("buff-sortie-defender", 30);
            defender.setSortieArmy("{\"infantry\":10000}");
            playerRepository.save(defender);
            createArmyUnit(defender.getId(), "infantry", 100000);
            items.saveAndFlush(new PlayerItem(null, defender.getId(), "rapidMobilizationOrder", 1, 0L));
            mobilization.use(defender.getId(), "rapidMobilizationOrder", 1, "battle-snapshot-use");
            var config = preferences.get(defender.getId());
            preferences.save(defender.getId(), new com.wargame.service.BattleActionPreferences.Preferences(
                    config.outgoing(), config.defending(), Map.of("infantry", 37500), config.formationKey()));
            March march = attack(defender, 0, Map.of("infantry", 100));
            assertEquals(Map.of("infantry", 100000), JsonUtil.parseIntMap(sessions.findById(march.getBattleId()).orElseThrow().getInitialDefender()));
            clock.addAndGet(com.wargame.model.constants.MobilizationRules.DURATION_MS);
            assertEquals(Map.of("infantry", 100000), preferences.selectSortieArmy(defender.getId(), Map.of("infantry", 100000)));
            assertEquals(Map.of("infantry", 100000), JsonUtil.parseIntMap(sessions.findById(march.getBattleId()).orElseThrow().getInitialDefender()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10000})
    void fullDefensePreservesOnlyPostSnapshotTroopsOnVictoryAndDefeat(int attackers) {
        Player defender = createTestPlayer("partial-sortie", 30);
        defender.setSortieArmy(JsonUtil.toJson(Map.of("infantry", 100)));
        playerRepository.save(defender);
        ArmyUnit infantry = createArmyUnit(defender.getId(), "infantry", 100);
        ArmyUnit rockets = createArmyUnit(defender.getId(), "rocket", 90);
        March march = attack(defender, 0, Map.of("infantry", attackers));
        BattleSession session = sessions.findById(march.getBattleId()).orElseThrow();
        assertEquals(Map.of("infantry", 100, "rocket", 90), JsonUtil.parseIntMap(session.getInitialDefender()));
        // 名单冻结后新增50名步兵和新工事；本场只扣开战名单内的实际损失。
        defender.setSortieArmy("{}");
        playerRepository.save(defender);
        infantry.setCount(150);
        armyUnitRepository.save(infantry);
        Fortification newFort = fort(defender.getId(), 0, 5);
        long deadline = session.getRoundDeadlineAt();
        for (int round = 0; round < 30 && sessions.findById(session.getId()).isPresent(); round++) {
            marchService.processTimedOutTacticalBattle(session.getId(), deadline + round * 15000L);
        }
        assertTrue(sessions.findById(session.getId()).isEmpty());
        int remaining = armyUnitRepository.findById(infantry.getId()).orElseThrow().getCount();
        assertTrue(remaining >= 50 && remaining <= 150, "战中新兵不承担本场战损");
        assertEquals(5, fortificationRepository.findById(newFort.getId()).orElseThrow().getCount());
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(march.getPlayerId()).get(0).getData());
        var survivors = JsonUtil.parseIntMap(JsonUtil.toJson(report.get("survivorDefender")));
        assertEquals(survivors.getOrDefault("infantry", 0) + 50, remaining);
        assertEquals(survivors.getOrDefault("rocket", 0), armyUnitRepository.findById(rockets.getId()).orElseThrow().getCount());
        assertEquals(attackers == 10000, report.get("win"));
    }

    @Test
    void legacyZeroSortieCannotExcludeTroopsOrLogistics() {
        Player defender = createTestPlayer("zero-sortie", 30);
        defender.setSortieArmy("{}");
        playerRepository.save(defender);
        createArmyUnit(defender.getId(), "infantry", 100000);
        createArmyUnit(defender.getId(), "truck", 500);
        createArmyUnit(defender.getId(), "transport", 600);
        fort(defender.getId(), 0, 10);
        March march = attack(defender, 0, Map.of("infantry", 100));
        assertEquals(Map.of("infantry", 100000, "truck", 500, "transport", 600, "bunker", 10), JsonUtil.parseIntMap(sessions.findById(march.getBattleId()).orElseThrow().getInitialDefender()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"infantry", "bunker"})
    void cityWallLevelDoesNotChangeActualTroopOrFortDamage(String target) {
        Double baselineDamage = null;
        for (int wallLevel : new int[] {0, 1, 10, 20}) {
            Player defender = createTestPlayer("wall-defender-" + target + wallLevel, 30);
            if (wallLevel > 0) createBuilding(defender.getId(), "wall", wallLevel);
            if ("bunker".equals(target)) fort(defender.getId(), 0, 10000);
            else createArmyUnit(defender.getId(), target, 10000);
            Player attacker = createTestPlayer("wall-attacker-" + target + wallLevel, 30);
            balancePower(attacker, defender);
            long now = System.currentTimeMillis();
            attacker.setWarAgainstId(defender.getId());
            attacker.setWarAt(now - 60000L);
            attacker.setWarEndAt(now + 600000L);
            playerRepository.save(attacker);
            PlayerCity city = new PlayerCity();
            city.setWorldId(createTestWorld().getId());
            city.setOwnerId(defender.getId());
            city.setCitySlot(0);
            city.setName("城防倍率测试城");
            city.setX(20 + wallLevel);
            city.setY(20);
            city = playerCityRepository.save(city);
            March march = createMarch(attacker.getId(), "player", city.getId().toString(), city.getName(),
                    10, 10, city.getX(), city.getY(), Map.of("rocket", 100), "conquer", now - 60000L, now - 1L, false, false);
            marchService.processMarches(attacker.getId(), now);
            BattleSession session = sessions.findById(march.getBattleId()).orElseThrow();
            // 固定交战距离并保持兵力一致，比较正式城市战斗的伤害而非随机击毁数量。
            session.setAttackerPositions(JsonUtil.toJson(Map.of("rocket", 0)));
            session.setDefenderPositions(JsonUtil.toJson(Map.of(target, 500)));
            sessions.save(session);
            Map<String, Object> response = marchService.executeTacticalRound(attacker.getId(), march.getId(),
                    new GameDtos.BattleCommandRequest(Map.of("rocket", new GameDtos.BattleUnitOrderRequest("hold", target)), 0));
            String log = (String) response.get("log");
            // 从结构化历史事件读取攻方实际伤害，不依赖新旧日志中的“我方”措辞。
            double actualDamage = JsonUtil.parseList(JsonUtil.toJson(response.get("formationEvents"))).stream()
                    .filter(e -> "FIRE".equals(e.get("type")) && "formation-0".equals(e.get("formationId")) && target.equals(e.get("targetUnit")))
                    .mapToDouble(e -> ((Number) e.get("damage")).doubleValue()).sum();
            assertTrue(actualDamage > 0, log);
            if (baselineDamage == null) baselineDamage = actualDamage;
            else assertEquals(baselineDamage, actualDamage, 1e-8, "城防等级" + wallLevel + "不应改变" + target + "承伤");
        }
    }

    @Test
    void incomingOfflineBattleUsesAllCityTroopsDespiteLegacyPreset() {
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
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(defender.getId()).get(0).getData());
        assertEquals(Map.of("infantry", 100000, "rocket", 50000), JsonUtil.parseIntMap(JsonUtil.toJson(report.get("initialDefender"))));
        var survivors = JsonUtil.parseIntMap(JsonUtil.toJson(report.get("survivorDefender")));
        assertEquals(survivors.getOrDefault("infantry", 0), armyUnitRepository.findById(infantry.getId()).orElseThrow().getCount());
        assertEquals(survivors.getOrDefault("rocket", 0), armyUnitRepository.findById(rockets.getId()).orElseThrow().getCount());
    }

    @Test
    void attackAllowsExactly25000AndRejects25001BeforeDeductingTroops() {
        Player attacker = createTestPlayer("capacity-boundary", 30);
        createBuilding(attacker.getId(), "wall", 10);
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
