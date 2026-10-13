package com.wargame;

import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 按真实的机动、交火和状态续存验证前三回合安全、持续前进第四回合接敌。 */
class BattleOpeningDistanceTest {
    private static final BattleService.TechCtx PLAIN = new BattleService.TechCtx(Map.of(), Map.of(), 0);

    @Test
    void openingKeepsAVisibleHundredPointSafetyGap() {
        BattleService service = new BattleService(0);
        assertEquals(1460, service.calcInitialDistance(Map.of("infantry", 1), Map.of("infantry", 1), null, null));
        assertEquals(3200, service.calcInitialDistance(Map.of("rocket", 1), Map.of("rocket", 1), null, null));
        assertEquals(3950, service.calcInitialDistance(Map.of("rocket", 1, "fighter", 1),
                Map.of("rocket", 1, "fighter", 1), null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"infantry", "rocket", "sub", "truck"})
    void opposingAdvancesEngageOnFourthRound(String unit) {
        assertOpening(Map.of(unit, 100), Map.of(unit, 100), PLAIN, PLAIN, true);
    }

    @Test
    void everyUnitPairKeepsThreeSafeRoundsAndEveryMobilePairEngagesOnFourth() {
        Map<String, Integer> speeds = new LinkedHashMap<>();
        UnitDef.UNITS.forEach((id, unit) -> speeds.put(id, unit.spd()));
        FortDef.FORTS.forEach((id, fort) -> speeds.put(id, fort.spd()));
        for (String attacker : speeds.keySet()) {
            for (String defender : speeds.keySet()) {
                if (speeds.get(attacker) + speeds.get(defender) == 0) continue;
                assertOpening(Map.of(attacker, 100), Map.of(defender, 100), PLAIN, PLAIN, true);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"infantry,bunker", "rocket,fighter", "sub,truck", "infantry,carrier"})
    void asymmetricAdvancesKeepThreeSafeRounds(String attacker, String defender) {
        assertOpening(Map.of(attacker, 100), Map.of(defender, 100), PLAIN, PLAIN, true);
    }

    @Test
    void mixedSpeedAndRangeDoNotAddAnExtraApproachRound() {
        // 最快战斗机和最远射程反坦克火箭车不是同一兵种，不能把两者组合成不存在的单位。
        Map<String, Integer> army = Map.of("fighter", 100, "rocket", 100);
        assertOpening(army, army, PLAIN, PLAIN, true);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 7, 100, 219})
    void fractionalSpeedUsesTheSameIntegerStepAsMovement(int knowledge) {
        var ctx = new BattleService.TechCtx(Map.of("arm_engine", 1, "weapon_range", 2), Map.of("blitz", 5), 0, 0,
                new CommandBonus(1L, "司令", 0, 0, 0, knowledge));
        assertOpening(Map.of("rocket", 100), Map.of("rocket", 100), ctx, ctx, true);
    }

    @Test
    void holdingOneSideCanDelayEngagementBeyondFourthRound() {
        assertOpening(Map.of("infantry", 100), Map.of("infantry", 100), PLAIN, PLAIN, false);
    }

    @Test
    void automaticWildAndWorldReportsUseTheSameOpening() {
        BattleService service = new BattleService(0);
        Map<String, Integer> army = Map.of("infantry", 100);
        String wild = service.resolveWild(army, army).getReport();
        String world = new BattleService(0).startWorldDispatch(army, army, Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), 0, 0, 0, 0, "conquer", Map.of(), 0).getReport();
        assertEquals(4, service.firstCombatRoundFromLog(wild), wild);
        assertEquals(4, service.firstCombatRoundFromLog(world), world);
    }

    /** 每回合续存位置、兵力与首次交战起点，避免单回合测试漏掉开局距离错误。 */
    private void assertOpening(Map<String, Integer> attacker, Map<String, Integer> defender,
                               BattleService.TechCtx attackerCtx, BattleService.TechCtx defenderCtx,
                               boolean defenderAdvances) {
        BattleService service = new BattleService(0);
        int distance = service.calcInitialDistance(attacker, defender, attackerCtx, defenderCtx);
        Map<String, Integer> attackerPositions = positions(attacker, 0);
        Map<String, Integer> defenderPositions = positions(defender, distance);
        Map<String, BattleService.UnitOrder> attackerOrders = orders(attacker, BattleService.CommandAction.ADVANCE);
        Map<String, BattleService.UnitOrder> defenderOrders = orders(defender, defenderAdvances
                ? BattleService.CommandAction.ADVANCE : BattleService.CommandAction.HOLD);
        int firstCombatRound = 0;
        StringBuilder report = new StringBuilder("初始距离: ").append(distance).append('\n');
        for (int round = 1; round <= 4; round++) {
            BattleRoundState state = service.resolveWorldRound(attacker, defender, attackerPositions, defenderPositions,
                    distance, attackerCtx.tech(), defenderCtx.tech(), attackerCtx.skills(), defenderCtx.skills(),
                    0, 0, 0, 0, 0, 0, round, firstCombatRound, attackerOrders, defenderOrders,
                    attackerCtx.commandBonus(), defenderCtx.commandBonus());
            report.append(state.log());
            assertEquals(round == 4 && defenderAdvances ? 4 : 0, state.firstCombatRound(), report.toString());
            if (round <= 3) {
                assertEquals(attacker, state.attackerArmy(), report.toString());
                assertEquals(defender, state.defenderArmy(), report.toString());
                assertFalse(state.log().contains(" 伤害"), report.toString());
            }
            attacker = state.attackerArmy();
            defender = state.defenderArmy();
            attackerPositions = state.attackerPositions();
            defenderPositions = state.defenderPositions();
            firstCombatRound = state.firstCombatRound();
        }
    }

    private Map<String, Integer> positions(Map<String, Integer> army, int position) {
        return army.keySet().stream().collect(Collectors.toMap(Function.identity(), id -> position));
    }

    private Map<String, BattleService.UnitOrder> orders(Map<String, Integer> army, BattleService.CommandAction action) {
        Map<String, BattleService.UnitOrder> result = new LinkedHashMap<>();
        army.keySet().forEach(id -> result.put(id, new BattleService.UnitOrder(action)));
        return result;
    }
}
