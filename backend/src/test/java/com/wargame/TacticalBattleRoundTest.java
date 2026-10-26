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
    void rocketOpeningDistanceUsesPlayerDefaultAndWildSceneInAutomaticLog() {
        BattleService service = new BattleService(0);
        Map<String, Integer> army = Map.of("rocket", 100);
        assertEquals(3450, service.calcInitialDistance(army, army, null, null));

        String report = service.resolveWild(army, army).getReport();
        assertTrue(report.contains("战场初始距离: 2100"), report);
        int firstFire = report.indexOf("；齐射");
        assertTrue(firstFire > report.indexOf("-- 第2回合 --"), report);
        assertTrue(firstFire < report.indexOf("-- 第3回合 --"), report);
        assertFalse(report.substring(0, report.indexOf("-- 第2回合 --")).contains("伤害"), report);
        assertTrue(report.contains("有效射程1600 目标距离1500"), report);
    }

    @Test
    void resolvesExactlyOneRoundWithoutAutoCompletingBattle() {
        BattleService service = new BattleService(0);
        BattleRoundState state = service.resolveWorldRound(
                Map.of("infantry", 20), Map.of("infantry", 20),
                Map.of("infantry", 0), Map.of("infantry", 1000), 1000,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 1,
                Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)), Map.of());

        assertEquals(1, state.round());
        assertFalse(state.finished());
        assertEquals(20, state.attackerArmy().get("infantry"));
        assertEquals(20, state.defenderArmy().get("infantry"));
    }

    @Test
    void finishesDefaultConquestAtThirtyFifthRoundWhenBothSidesSurvive() {
        assertEquals(35, BattleService.CONQUER_MAX_ROUND);
        BattleService service = new BattleService(0);
        BattleRoundState state = service.resolveWorldRound(
                Map.of("truck", 1), Map.of("truck", 1),
                Map.of("truck", 0), Map.of("truck", 1000), 1000,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, BattleService.CONQUER_MAX_ROUND,
                Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)),
                Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));

        assertEquals(BattleService.CONQUER_MAX_ROUND, state.round());
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
                0, 0, 0, 0, 1,
                Map.of(), Map.of());

        assertEquals(1, state.round());
        assertTrue(state.finished(), "任一方兵力归零后应立即结束战斗");
        assertTrue(state.attackerWin());
        assertTrue(state.defenderArmy().isEmpty());
    }

    @Test
    void defeatedUnitMovementPrecedesTheAttackThatEliminatesItInReport() {
        BattleRoundState state = new BattleService(0).resolveWorldRound(
                Map.of("fighter", 1_000), Map.of("infantry", 35),
                Map.of("fighter", 1_000), Map.of("infantry", 1_700), 2_300,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 3,
                Map.of(), Map.of());

        String report = state.log();
        int movement = report.indexOf("敌方步兵");
        int kill = report.indexOf(" 击毁35");
        assertTrue(movement >= 0 && kill >= 0, report);
        assertTrue(movement < kill, report);
        assertFalse(report.substring(kill).contains("敌方步兵"), report);
        assertTrue(state.defenderArmy().isEmpty(), report);
    }

    @Test
    void combinesAdvanceWithItsFirstAttackWhenTheEnemyEntersRange() {
        BattleService service = new BattleService(0);
        BattleRoundState state = service.resolveWorldRound(
                Map.of("rocket", 1), Map.of("rocket", 1),
                Map.of("rocket", 0), Map.of("rocket", 1_900), 1_900,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 1,
                Map.of(), Map.of());

        String report = state.log();
        String attackerPrefix = "我方反坦克火箭-喀秋莎（BM-13）(1)";
        String attackerLine = report.lines()
                .filter(line -> line.startsWith(attackerPrefix))
                .findFirst()
                .orElseThrow();

        assertTrue(attackerLine.contains("[前进] 推进150 -> 坐标150 (距敌1600)"), report);
        assertTrue(attackerLine.contains("；齐射敌反坦克火箭-喀秋莎（BM-13）"), report);
        assertTrue(attackerLine.contains("有效射程1600 目标距离1600"), report);
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
        for (int round = 1; round <= 7; round++) {
            BattleRoundState state = service.resolveWorldRound(
                    Map.of("rocket", 1), Map.of("truck", 100_000),
                    attackerPositions, defenderPositions, 1_000,
                    Map.of(), Map.of(), Map.of(), Map.of(),
                    0, 0, 0, 0, round,
                    Map.of(), Map.of());
            assertEquals(Math.min(round * 150, 1_000), state.attackerPositions().get("rocket"), state.log());
            assertEquals(1_000, state.defenderPositions().get("truck"));
            assertFalse(state.log().contains("我方反坦克火箭-喀秋莎（BM-13）(1) [待命]"));
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
                0, 0, 0, 0, 1,
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
                Map.of("rocket", 0, "truck", 0), Map.of("armored", 300, "htank", 1_700), 1_700,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 1,
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
                0, 0, 0, 0, 1,
                Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE)),
                Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE)));
        assertEquals(50, state.attackerPositions().get("truck"));
        assertEquals(50, state.defenderPositions().get("truck"));
    }

    @Test
    void rocketCountersOnlyFourArmoredTargetsInDamageCalculation() {
        for (String target : new String[]{"ltank", "htank", "armored", "assault"}) {
            assertEquals(3.0, BattleRules.multiplier("rocket", target), target);

            var unit = UnitDef.UNITS.get(target);
            // 科技重构为+5%/级（最高+50%），10级防御科技倍率为 1 + 10 * 0.05 = 1.5。
            double defense = unit.def() * 1.5;
            long expectedDamage = Math.round(10_000 * 150.0 * 3.0 * 100.0 / (100.0 + 5 * defense));
            BattleRoundState state = new BattleService(0).resolveWorldRound(
                    Map.of("rocket", 10_000), Map.of(target, 100_000),
                    Map.of("rocket", 0), Map.of(target, 1_600), 1_600,
                    Map.of("attack_tech", 10), Map.of("defense_tech", 10, "cmd_hp", 10),
                    Map.of(), Map.of(), 0, 0, 0, 0, 1,
                    Map.of("rocket", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)),
                    Map.of(target, new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));

            assertTrue(state.log().contains("倍率×3.0 相克"), target + ": " + state.log());
            assertTrue(state.log().contains("对地攻击150 本次原始火力1500000 伤害" + expectedDamage),
                    target + ": " + state.log());
        }

        assertEquals(1.0, BattleRules.multiplier("rocket", "special"));
        assertEquals(1.0, BattleRules.multiplier("rocket", "howitzer"));
        assertEquals(1.0, BattleRules.multiplier("rocket", "fighter"));
        assertEquals(1.0, BattleRules.multiplier("htank", "ltank"));
        assertEquals(2.5, BattleRules.multiplier("armored", "fighter"));
        assertEquals(2.5, BattleRules.multiplier("armored", "bomber"));
        assertEquals(1.0, BattleRules.multiplier("armored", "infantry"));

        BattleRoundState airState = new BattleService(0).resolveWorldRound(
                Map.of("armored", 10_000), Map.of("fighter", 100_000),
                Map.of("armored", 0), Map.of("fighter", 300), 300,
                Map.of("attack_tech", 10), Map.of("defense_tech", 10, "cmd_hp", 10),
                Map.of(), Map.of(), 0, 0, 0, 0, 1,
                Map.of("armored", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)),
                Map.of("fighter", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));
        assertTrue(airState.log().contains("倍率×2.5 相克"), airState.log());
    }
}
