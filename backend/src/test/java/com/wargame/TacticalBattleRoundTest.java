package com.wargame;

import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证战术指挥接口只结算调用方要求的一回合。 */
class TacticalBattleRoundTest {

    @Test
    void resolvesExactlyOneRoundWithoutAutoCompletingBattle() {
        BattleService service = new BattleService(0);
        BattleRoundState state = service.resolveWorldRound(
                Map.of("infantry", 20), Map.of("infantry", 20),
                Map.of("infantry", 0), Map.of("infantry", 1000), 1000,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 0, 0, 1,
                Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)), Map.of());

        assertEquals(1, state.round());
        assertFalse(state.finished());
        assertEquals(20, state.attackerArmy().get("infantry"));
        assertEquals(20, state.defenderArmy().get("infantry"));
    }

    @Test
    void finishesBattleAtThirtiethRoundWhenBothSidesSurvive() {
        BattleService service = new BattleService(0);
        BattleRoundState state = service.resolveWorldRound(
                Map.of("truck", 1), Map.of("truck", 1),
                Map.of("truck", 0), Map.of("truck", 1000), 1000,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 0, 0, BattleService.MAX_ROUND,
                Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)),
                Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));

        assertEquals(BattleService.MAX_ROUND, state.round());
        assertTrue(state.finished());
        assertFalse(state.attackerWin());
    }

    @Test
    void finishesImmediatelyWhenOneSideHasNoSurvivingUnits() {
        BattleService service = new BattleService(0);
        BattleRoundState state = service.resolveWorldRound(
                Map.of("htank", 1_000), Map.of("infantry", 1),
                Map.of("htank", 0), Map.of("infantry", 0), 1_000,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 0, 0, 1,
                Map.of(), Map.of());

        assertEquals(1, state.round());
        assertTrue(state.finished(), "任一方兵力归零后应立即结束战斗");
        assertTrue(state.attackerWin());
        assertTrue(state.defenderArmy().isEmpty());
    }

    @Test
    void combinesAdvanceWithItsFirstAttackWhenTheEnemyEntersRange() {
        BattleService service = new BattleService(0);
        BattleRoundState state = service.resolveWorldRound(
                Map.of("rocket", 1), Map.of("rocket", 1),
                Map.of("rocket", 0), Map.of("rocket", 2_100), 2_100,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 0, 0, 1,
                Map.of(), Map.of());

        String report = state.log();
        String attackerPrefix = "我方火箭-喀秋莎（BM-13）(1)";
        String attackerLine = report.lines()
                .filter(line -> line.startsWith(attackerPrefix))
                .findFirst()
                .orElseThrow();

        assertTrue(attackerLine.contains("[前进] 推进250 -> 坐标250 (距敌1850)"), report);
        assertTrue(attackerLine.contains("；齐射敌火箭-喀秋莎（BM-13）"), report);
        assertFalse(attackerLine.contains("；" + attackerPrefix), report);
        assertEquals(1, report.lines()
                .filter(line -> line.startsWith(attackerPrefix))
                .count());
    }

    @Test
    void uncommandedRocketAdvancesThroughRangeUntilContact() {
        BattleService service = new BattleService(0);
        Map<String, Integer> attackerPositions = Map.of("rocket", 0);
        Map<String, Integer> defenderPositions = Map.of("truck", 1_000);
        for (int round = 1; round <= 4; round++) {
            BattleRoundState state = service.resolveWorldRound(
                    Map.of("rocket", 1), Map.of("truck", 100_000),
                    attackerPositions, defenderPositions, 1_000,
                    Map.of(), Map.of(), Map.of(), Map.of(),
                    0, 0, 0, 0, 0, 0, round,
                    Map.of(), Map.of());
            assertEquals(round * 250, state.attackerPositions().get("rocket"), state.log());
            assertEquals(1_000, state.defenderPositions().get("truck"));
            assertFalse(state.log().contains("我方火箭-喀秋莎（BM-13）(1) [待命]"));
            attackerPositions = state.attackerPositions();
            defenderPositions = state.defenderPositions();
        }
    }

    @Test
    void reportPrintsRocketAttackBeforeEnemyFighterAttack() {
        Map<String, Integer> attacker = Map.of("fighter", 10_000);
        Map<String, Integer> defender = new LinkedHashMap<>();
        defender.put("fighter", 100_000);
        defender.put("rocket", 100_000);

        BattleRoundState state = new BattleService(0).resolveWorldRound(
                attacker, defender,
                Map.of("fighter", 0), Map.of("fighter", 300, "rocket", 300), 300,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 0, 0, 1,
        Map.of(), Map.of());

        String report = state.log();
        int rocketAttack = report.indexOf("齐射");
        int fighterAttack = report.indexOf("空战");
        assertTrue(rocketAttack >= 0, report);
        assertTrue(fighterAttack >= 0, report);
        assertTrue(rocketAttack < fighterAttack, report);
    }

    @Test
    void spilloverAppearsWithTheSameUnitWithinOneRound() {
        BattleRoundState state = new BattleService(0).resolveWorldRound(
                Map.of("rocket", 100, "truck", 1), Map.of("armored", 1, "htank", 100_000),
                Map.of("rocket", 0, "truck", 0), Map.of("armored", 300, "htank", 2_000), 2_000,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 0, 0, 1,
                Map.of("rocket", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE)),
                Map.of("armored", new BattleService.UnitOrder(BattleService.CommandAction.HOLD),
                        "htank", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));

        String report = state.log();
        String rocketLine = report.lines().filter(line -> line.startsWith("我方" + UnitDef.UNITS.get("rocket").name()))
                .findFirst().orElseThrow();
        assertTrue(rocketLine.contains("[前进]"), report);
        assertTrue(rocketLine.contains("齐射敌" + UnitDef.UNITS.get("armored").name()), report);
        assertTrue(rocketLine.contains("余伤攻击敌" + UnitDef.UNITS.get("htank").name()), report);
        assertEquals(1, report.lines().filter(line -> line.startsWith("我方" + UnitDef.UNITS.get("rocket").name())).count(), report);
    }

    @Test
    void opposingAdvancesMeetWithoutCrossing() {
        BattleRoundState state = new BattleService(0).resolveWorldRound(
                Map.of("truck", 1), Map.of("truck", 1),
                Map.of("truck", 0), Map.of("truck", 100), 100,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 0, 0, 1,
                Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE)),
                Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE)));
        assertEquals(50, state.attackerPositions().get("truck"));
        assertEquals(50, state.defenderPositions().get("truck"));
    }

    @Test
    void rocketCountersOnlyFourArmoredTargetsInDamageCalculation() {
        for (String target : new String[]{"ltank", "htank", "armored", "assault"}) {
            assertEquals(10.0, BattleRules.multiplier("rocket", target), target);

            var unit = UnitDef.UNITS.get(target);
            double defense = unit.def() * 2 * 1.5;
            long expectedDamage = Math.round(10_000 * 200.0 * 10.0 * 100.0 / (100.0 + 5 * defense));
            BattleRoundState state = new BattleService(0).resolveWorldRound(
                    Map.of("rocket", 10_000), Map.of(target, 100_000),
                    Map.of("rocket", 0), Map.of(target, 2_000), 2_000,
                    Map.of("attack_tech", 10), Map.of("defense_tech", 10, "cmd_hp", 10),
                    Map.of(), Map.of(), 0, 0, 0, 0, 0, 10, 1,
                    Map.of("rocket", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)),
                    Map.of(target, new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));

            assertTrue(state.log().contains("倍率×10.0 相克"), target + ": " + state.log());
            assertTrue(state.log().contains("对地攻击200 本次原始火力2000000 伤害" + expectedDamage),
                    target + ": " + state.log());
        }

        assertEquals(1.0, BattleRules.multiplier("rocket", "special"));
        assertEquals(1.0, BattleRules.multiplier("rocket", "howitzer"));
        assertEquals(1.0, BattleRules.multiplier("rocket", "fighter"));
        assertEquals(1.0, BattleRules.multiplier("htank", "ltank"));
        assertEquals(5.0, BattleRules.multiplier("armored", "fighter"));
        assertEquals(5.0, BattleRules.multiplier("armored", "bomber"));
        assertEquals(1.0, BattleRules.multiplier("armored", "infantry"));

        BattleRoundState airState = new BattleService(0).resolveWorldRound(
                Map.of("armored", 10_000), Map.of("fighter", 100_000),
                Map.of("armored", 0), Map.of("fighter", 300), 300,
                Map.of("attack_tech", 10), Map.of("defense_tech", 10, "cmd_hp", 10),
                Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 1,
                Map.of("armored", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)),
                Map.of("fighter", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));
        assertTrue(airState.log().contains("倍率×5.0 相克"), airState.log());
    }
}
