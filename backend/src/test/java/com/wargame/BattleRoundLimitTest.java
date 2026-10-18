package com.wargame;

import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 用无法交火的双方隔离回合边界，再核验最后一回合胜利优先于超时撤退。 */
class BattleRoundLimitTest {
    private BattleRoundState stalledRound(String action, int round) {
        var hold = Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.HOLD));
        return new BattleService(1).resolveWorldRound(Map.of("truck", 1), Map.of("truck", 1),
                Map.of("truck", 0), Map.of("truck", 1000), 1000,
                Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, round, 0,
                hold, hold, CommandBonus.NONE, CommandBonus.NONE, action);
    }

    @ParameterizedTest
    @CsvSource({"plunder,25", "conquer,35", "tactical_plunder,25", "feint,10", "harass,15", "guerrilla,5"})
    void bothSidesSurvivePastOldLimitAndWithdrawOnlyAtTheirActionLimit(String action, int limit) {
        assertFalse(stalledRound(action, Math.min(24, limit - 1)).finished());
        assertFalse(stalledRound(action, limit - 1).finished());
        var finalRound = stalledRound(action, limit);
        assertTrue(finalRound.finished());
        assertFalse(finalRound.attackerWin());
        assertEquals(Map.of("truck", 1), finalRound.attackerArmy());
        assertEquals(Map.of("truck", 1), finalRound.defenderArmy());
        assertTrue(finalRound.log().contains("回合耗尽,被迫撤退"));
    }

    @ParameterizedTest
    @CsvSource({"plunder,25", "conquer,35", "feint,10", "harass,15", "guerrilla,5"})
    void automaticDispatchRunsExactlyTheSameLimit(String action, int limit) {
        var hold = Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.HOLD));
        var result = new BattleService(1).startWorldDispatch(Map.of("truck", 1), Map.of("truck", 1), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, action, Map.of(), 0, true, hold, hold);
        assertFalse(result.isWin());
        assertEquals(limit, result.getReport().lines().filter(line -> line.startsWith("-- 第")).count());
        assertTrue(result.getReport().contains("-- 第" + limit + "回合 --"));
        assertFalse(result.getReport().contains("-- 第" + (limit + 1) + "回合 --"));
    }

    @ParameterizedTest
    @CsvSource({"plunder,25", "conquer,35", "feint,10", "harass,15", "guerrilla,5"})
    void destroyingEnemyOnLastAllowedRoundStillWins(String action, int limit) {
        var result = new BattleService(1).resolveWorldRound(Map.of("htank", 1000), Map.of("infantry", 1),
                Map.of("htank", 0), Map.of("infantry", 0), 1000,
                Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, limit, 1,
                Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, action);
        assertTrue(result.finished());
        assertTrue(result.attackerWin());
        assertFalse(result.log().contains("回合耗尽"));
    }

    @Test
    void practiceKeepsItsIndependentTwentyFourRoundBoundary() {
        var hold = Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.HOLD));
        var ctx = new BattleService.TechCtx(Map.of(), Map.of(), 0, 0);
        var engine = new BattleService(1);
        assertFalse(engine.resolvePracticeRound(Map.of("truck", 1), Map.of("truck", 1),
                Map.of("truck", 0), Map.of("truck", 1000), 1000, ctx, ctx, 23, 0, hold, hold).finished());
        assertTrue(engine.resolvePracticeRound(Map.of("truck", 1), Map.of("truck", 1),
                Map.of("truck", 0), Map.of("truck", 1000), 1000, ctx, ctx, 24, 0, hold, hold).finished());
        assertFalse(stalledRound(null, 34).finished());
        assertTrue(stalledRound(null, 35).finished());
    }

    @ParameterizedTest
    @ValueSource(strings = {"feint", "harass", "guerrilla"})
    void harassmentVictoryCannotLootAnyResourceOrConquer(String action) {
        var result = new BattleService(1).startWorldDispatch(Map.of("htank", 1000), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, action,
                Map.of("food", 1000, "steel", 1000, "oil", 1000, "rare", 1000, "gold", 1000, "diamond", 20),
                0, true, Map.of(), Map.of());
        assertTrue(result.isWin());
        assertFalse(result.isCityConquered());
        assertTrue(result.getPlunderedResources().values().stream().allMatch(amount -> amount == 0));
        assertTrue(result.getReport().contains("不夺取资源、不占领目标"));
    }
}
