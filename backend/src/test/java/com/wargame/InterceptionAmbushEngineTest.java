package com.wargame;

import com.wargame.model.constants.BattleScene;
import com.wargame.model.dto.BattleResult;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InterceptionAmbushEngineTest {
    private final BattleService engine = new BattleService(0);
    private BattleResult battle(Map<String, Integer> incoming, Map<String, Integer> defenders, boolean ambush,
                                Map<String, Integer> attackerSkills, Map<String, Integer> defenderSkills) {
        return new BattleService(0).startWorldDispatch(incoming, defenders, Map.of(), Map.of(), Map.of(), attackerSkills, defenderSkills,
                0, 0, 0, 0, "intercept_scout", Map.of(), 0, true, Map.of(), Map.of(),
                CommandBonus.NONE, CommandBonus.NONE, null, BattleScene.PLAYER, ambush);
    }
    private String round(BattleResult result, int round) {
        return result.getReport().split("-- 第" + round + "回合 --\\n")[1].split("-- 第")[0];
    }
    @Test
    void slowerShorterRangeInterceptorFiresFirstWithoutMovementAndIncomingUsesSurvivors() {
        var result = battle(Map.of("rocket", 1_000), Map.of("infantry", 1_000), true, Map.of(), Map.of());
        String first = round(result, 1), second = round(result, 2);
        assertTrue(first.indexOf("敌方步兵") < first.indexOf("我方反坦克火箭"), first);
        assertFalse(first.contains("[前进]"), first);
        assertFalse(first.contains("贴脸×"), first);
        assertEquals(1, first.lines().filter(l -> l.startsWith("敌方步兵") && l.contains("击毁")).count(), first);
        assertFalse(first.contains("我方反坦克火箭-喀秋莎（BM-13）(1000)"), first);
        assertTrue(second.indexOf("我方反坦克火箭") < second.indexOf("敌方步兵"), second);
        assertEquals(160, result.getInterceptionOpening().get("distance"));
        assertEquals(true, result.getInterceptionOpening().get("ambushConsumed"));
        assertEquals(1, result.getInterceptionOpening().get("firstCombatRound"));
    }
    @Test
    void mixedInfantryDoesNotGainTankRangeOrExtraFire() {
        var result = battle(Map.of("rocket", 1_000), Map.of("htank", 500, "infantry", 1_000), true, Map.of(), Map.of());
        String first = round(result, 1);
        assertEquals(320, result.getInterceptionOpening().get("distance"));
        assertTrue(first.contains("敌方重型坦克"), first);
        assertFalse(first.lines().anyMatch(l -> l.startsWith("敌方步兵") && l.contains("击毁")), first);
        assertFalse(first.contains("贴脸×"), first);
        assertTrue(round(result, 2).contains("敌方步兵"));
    }
    @Test
    void validCounterStillFiresAndSkillWindowsStartAtFirstContactWithoutExtraActivation() {
        var result = battle(Map.of("infantry", 1_000), Map.of("infantry", 1_000), true,
                Map.of("counter", 5), Map.of("frenzy", 5));
        assertTrue(round(result, 1).contains("绝境反击"), result.getReport());
        assertTrue(round(result, 1).contains("全军冲锋"));
        assertFalse(round(result, 2).contains("全军冲锋"));
        assertFalse(round(result, 3).contains("全军冲锋"));
        assertTrue(round(result, 4).contains("全军冲锋"));
        assertEquals(1, round(result, 1).lines().filter(l -> l.startsWith("敌方步兵") && l.contains("击毁")).count());
    }
    @Test
    void invalidAndZeroRangeUnitsDoNotCreateAmbushAndEmergencyKeepsNormalOpening() {
        assertEquals(0, engine.ambushDistance(Map.of("truck", 10, "rocket", 0, "unknown", 100), Map.of("infantry", 10), null));
        var fallback = battle(Map.of("infantry", 10), Map.of("truck", 10), true, Map.of(), Map.of());
        assertEquals("urgent", fallback.getInterceptionOpening().get("mode"));
        assertEquals(false, fallback.getInterceptionOpening().get("ambushConsumed"));
        var ordinary = battle(Map.of("rocket", 1_000), Map.of("infantry", 1_000), false, Map.of(), Map.of());
        assertEquals(3450, ordinary.getInterceptionOpening().get("distance"));
        assertFalse(ordinary.getReport().contains("伏击发动"));
    }
    @Test
    void calibrationUsesCurrentTroopsAndNoExtraDamageMultiplier() {
        for (var defenders : java.util.List.of(Map.of("infantry", 1_000), Map.of("htank", 500, "infantry", 1_000))) {
            for (boolean ambush : new boolean[]{false, true}) {
                var result = battle(Map.of("rocket", 1_000), defenders, ambush, Map.of(), Map.of());
                System.out.println("AMBUSH_CALIBRATION incoming=rocket:1000 defenders=" + defenders + " ambush=" + ambush
                        + " attackerWin=" + result.isWin() + " attacker=" + result.getSurvivorAttacker() + " defender=" + result.getSurvivorDefender());
            }
        }
    }
}
