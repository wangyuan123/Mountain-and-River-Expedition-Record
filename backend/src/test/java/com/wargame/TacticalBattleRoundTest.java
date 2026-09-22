package com.wargame;

import com.wargame.model.dto.BattleRoundState;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;

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

        assertTrue(attackerLine.contains("[前进] 推进100 -> 坐标100 (距敌2000)"), report);
        assertTrue(attackerLine.contains("；" + attackerPrefix + "齐射敌火箭-喀秋莎（BM-13）"), report);
        assertEquals(1, report.lines()
                .filter(line -> line.startsWith(attackerPrefix))
                .count());
    }
}
