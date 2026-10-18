package com.wargame;

import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.UnitEquipmentEffects;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.ConquestWallState;
import com.wargame.service.BattleService;
import com.wargame.service.pvp.PvpPowerModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实回合内核验证连续墙损、阶段边界、35回合终局及不参与部队统计。 */
class ConquestWallBreachTest {
    private final BattleService engine = new BattleService(1);
    private static final Map<String, Integer> MAX_TECH = Map.of("attack_tech", 10, "fire_density", 10, "weapon_range", 10, "arm_engine", 10);

    private ConquestWallState wall(int level, boolean defenders) {
        return ConquestWallState.create(level, defenders, BattleRules.VERSION);
    }

    private BattleRoundState round(Map<String, Integer> army, Map<String, Integer> enemies,
            Map<String, Integer> positions, int distance, int round, int first, Map<String, Integer> tech,
            Map<String, BattleService.UnitOrder> orders, ConquestWallState wall) {
        return engine.resolveWorldRound(army, enemies, positions, Map.of("infantry", distance, "bunker", distance), distance,
                tech, Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, round, first, orders, Map.of(),
                CommandBonus.NONE, CommandBonus.NONE, "conquer", wall);
    }

    @ParameterizedTest
    @CsvSource({"0,0,0", "1,250000,20", "2,500000,40", "3,750000,60", "4,1000000,80",
            "5,1250000,100", "6,1500000,120", "7,1750000,140", "8,2000000,160", "9,2250000,180", "10,2500000,200"})
    void levelsMatchApprovedGrowth(int level, long hp, int defense) {
        assertEquals(0, wall(level, false).maxHp().compareTo(BigDecimal.valueOf(hp)));
        assertEquals(defense, wall(level, false).defense());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 15})
    void fiveThousandMaxTechSpecialBreachesOnNinthContactAttack(int gear) {
        var tech = new java.util.LinkedHashMap<>(MAX_TECH);
        tech.put(UnitEquipmentEffects.key("special", "fort"), gear);
        var state = wall(10, false);
        int finalAttack = gear == 0 ? 9 : 8;
        for (int r = 1; r <= finalAttack; r++) {
            var result = round(Map.of("special", 5000), Map.of(), Map.of("special", 1000), 1000, r, 1, tech, Map.of(), state);
            assertEquals(Map.of("special", 5000), result.attackerArmy());
            assertTrue(result.defenderArmy().isEmpty());
            assertEquals(r == finalAttack, result.finished(), result.log());
            assertEquals(r == finalAttack, result.attackerWin());
            state = result.wall();
            if (r == finalAttack - 1) assertTrue(state.standing());
            if (r == finalAttack) {
                assertEquals(0, state.hpRemaining().signum());
                assertEquals("WALL_BREACHED", result.terminalReason());
            }
        }
    }

    @Test
    void emptyCityGeometryAndAutomaticResultMatchManualRounds() {
        var ctx = new BattleService.TechCtx(MAX_TECH, Map.of(), 0, 0);
        int distance = engine.calcInitialDistance(Map.of("special", 5000), Map.of(), ctx, ctx, wall(10, false));
        assertEquals(2120, distance);
        var positions = Map.of("special", 0);
        var state = wall(10, false);
        int first = 0;
        int attacks = 0;
        for (int r = 1; r <= 13; r++) {
            var result = round(Map.of("special", 5000), Map.of(), positions, distance, r, first, MAX_TECH, Map.of(), state);
            if (result.log().contains("攻击敌城墙")) attacks++;
            assertEquals(Math.min(450 * r, distance), result.attackerPositions().get("special"));
            assertEquals(r == 13, result.finished());
            if (r == 12) assertTrue(result.wall().standing());
            positions = result.attackerPositions(); state = result.wall(); first = result.firstCombatRound();
        }
        assertEquals(5, first);
        assertEquals(9, attacks);
        var automatic = engine.startWorldDispatch(Map.of("special", 5000), Map.of(), Map.of(), MAX_TECH,
                Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, "conquer", Map.of(), 0, true,
                Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, wall(10, false));
        assertTrue(automatic.isWin());
        assertEquals(13, automatic.getReport().lines().filter(l -> l.startsWith("-- 第")).count());
        assertEquals(state.hpRemaining(), automatic.getWall().hpRemaining());
        assertEquals(0, automatic.getExpGained());
        assertTrue(automatic.getInitialDefender().isEmpty());
        assertTrue(automatic.getSurvivorDefender().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"infantry", "bunker", "infantry~support"})
    void clearingLastDefenderOrFortSharesRemainingActionsWithWall(String target) {
        var result = engine.resolveWorldRound(Map.of("special", 5000), Map.of(target, 1), Map.of("special", 1000),
                Map.of(target, 1000), 1000, MAX_TECH, Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 1, 0,
                Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, "conquer", wall(10, true));
        assertTrue(result.defenderArmy().isEmpty());
        assertFalse(result.finished());
        assertTrue(result.wall().hpRemaining().compareTo(wall(10, true).hpRemaining()) < 0);
        var fullAttack = round(Map.of("special", 5000), Map.of(), Map.of("special", 1000), 1000, 1, 0, MAX_TECH, Map.of(), wall(10, false));
        // 击杀守军已耗掉部分额度，因此本回合墙损必须小于直接打墙的完整额度。
        assertTrue(result.wall().hpRemaining().compareTo(fullAttack.wall().hpRemaining()) > 0);
        assertEquals(ConquestWallState.ATTACK_WALL, result.wall().phase());
        assertTrue(result.log().contains("余伤攻击敌城墙"));
        var next = round(result.attackerArmy(), result.defenderArmy(), result.attackerPositions(), 1000, 2,
                result.firstCombatRound(), MAX_TECH, Map.of(), result.wall());
        assertTrue(next.wall().hpRemaining().compareTo(result.wall().hpRemaining()) < 0);
        assertEquals(result.attackerPositions(), next.attackerPositions());
        assertEquals(result.firstCombatRound(), next.firstCombatRound());
    }

    @Test
    void overflowOutOfWallRangeWaitsWithoutExtraMovement() {
        var result = engine.resolveWorldRound(Map.of("special", 5000), Map.of("infantry", 1), Map.of("special", 500),
                Map.of("infantry", 500), 1000, MAX_TECH, Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 1, 0,
                Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, "conquer", wall(10, true));
        assertTrue(result.defenderArmy().isEmpty());
        assertEquals(500, result.attackerPositions().get("special"));
        assertEquals(wall(10, true).hpRemaining(), result.wall().hpRemaining());
        assertFalse(result.log().contains("攻击敌城墙"));
        var next = round(result.attackerArmy(), result.defenderArmy(), result.attackerPositions(), 1000, 2, 1, MAX_TECH, Map.of(), result.wall());
        assertEquals(950, next.attackerPositions().get("special"));
        assertTrue(next.wall().hpRemaining().compareTo(result.wall().hpRemaining()) < 0);
    }

    @Test
    void notYetActedUnitsAttackWallUsingOnlyTheirOwnBudget() {
        var positions = Map.of("rocket", 1000, "special", 1000);
        var single = engine.resolveWorldRound(Map.of("rocket", 5000), Map.of("infantry", 1), positions,
                Map.of("infantry", 1000), 1000, MAX_TECH, Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 1, 0,
                Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, "conquer", wall(10, true));
        var mixed = engine.resolveWorldRound(Map.of("rocket", 5000, "special", 5000), Map.of("infantry", 1), positions,
                Map.of("infantry", 1000), 1000, MAX_TECH, Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 1, 0,
                Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, "conquer", wall(10, true));
        var addedDamage = single.wall().hpRemaining().subtract(mixed.wall().hpRemaining());
        assertEquals(288409.090909, addedDamage.doubleValue(), .000001);
        assertTrue(mixed.log().contains("余伤攻击敌城墙"));
        assertTrue(mixed.defenderArmy().isEmpty());
    }

    @Test
    void finalRoundOverflowCanBreachAndWinWithoutExtraRound() {
        var result = round(Map.of("special", 5000), Map.of("infantry", 1), Map.of("special", 1000), 1000, 35, 1, MAX_TECH, Map.of(), wall(1, true));
        assertTrue(result.attackerWin()); assertTrue(result.finished());
        assertEquals("WALL_BREACHED", result.terminalReason());
        assertEquals(0, result.wall().hpRemaining().signum());
    }

    @Test
    void holdRetreatAndPartialHpPersistWithoutRoundingOrHealing() {
        var hold = Map.of("special", new BattleService.UnitOrder(BattleService.CommandAction.HOLD));
        var far = round(Map.of("special", 5000), Map.of(), Map.of("special", 0), 1000, 1, 0, MAX_TECH, hold, wall(10, false));
        assertEquals(wall(10, false).hpRemaining(), far.wall().hpRemaining());
        assertEquals(0, far.firstCombatRound());
        var hit = round(Map.of("special", 5000), Map.of(), Map.of("special", 1000), 1000, 2, 1, MAX_TECH, hold, wall(10, false));
        var restored = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(com.wargame.util.JsonUtil.parseTree(com.wargame.util.JsonUtil.toJson(hit.wall())), ConquestWallState.class);
        assertEquals(hit.wall(), restored);
        var retreat = round(Map.of("special", 5000), Map.of(), hit.attackerPositions(), 1000, 3, 1, MAX_TECH,
                Map.of("special", new BattleService.UnitOrder(BattleService.CommandAction.RETREAT)), restored);
        assertEquals(550, retreat.attackerPositions().get("special"));
        assertEquals(hit.wall().hpRemaining(), retreat.wall().hpRemaining());
    }

    @Test
    void wallIgnoresDefenderTechCommanderEquipmentAndLearningSources() {
        var neutral = round(Map.of("special", 5000), Map.of(), Map.of("special", 1000), 1000, 3, 1, MAX_TECH, Map.of(), wall(10, false));
        var boosted = engine.resolveWorldRound(Map.of("special", 5000), Map.of(), Map.of("special", 1000), Map.of(), 1000,
                MAX_TECH, Map.of("defense_tech", 10, "cmd_hp", 10, UnitEquipmentEffects.key("wall", "hp"), 20),
                Map.of("learn", 5), Map.of("bulwark", 5, "suppress", 5, "counter", 5), 0, 0, 1000, 1000,
                3, 1, Map.of(), Map.of(), CommandBonus.NONE, new CommandBonus(1L, "守城司令", 1000, 1000, 1000, 1000), "conquer", wall(10, false));
        assertEquals(neutral.wall(), boosted.wall());
        assertEquals(Map.of("special", 5000), boosted.attackerArmy());
    }

    @Test
    void attackerOfficerAndSkillsShareFirstCombatCycleAcrossWallPhase() {
        var wall = wall(10, false);
        // 使用20点军事使第4回合伤害低于剩余墙体生命，避免破墙截断掩盖属性倍率。
        var boosted = engine.resolveWorldRound(Map.of("special", 5000), Map.of(), Map.of("special", 1000), Map.of(), 1000,
                MAX_TECH, Map.of(), Map.of("frenzy", 5, "pierce", 5), Map.of(), 20, 0, 0, 0,
                3, 1, Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, "conquer", wall);
        double baseline = 3_172_500.0 / 11;
        assertEquals(baseline, wall.hpRemaining().subtract(boosted.wall().hpRemaining()).doubleValue(), .000001,
                "第1回合首次交火，第3回合军事属性和技能均不生效");
        var skillRound = engine.resolveWorldRound(Map.of("special", 5000), Map.of(), Map.of("special", 1000), Map.of(), 1000,
                MAX_TECH, Map.of(), Map.of("frenzy", 5, "pierce", 5), Map.of(), 20, 0, 0, 0,
                4, 1, Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, "conquer", boosted.wall());
        assertEquals(3_172_500.0 * 1.2 * 1.5 / 8, boosted.wall().hpRemaining().subtract(skillRound.wall().hpRemaining()).doubleValue(), .000001,
                "第4回合属性和技能共同生效，不能因转攻墙重置周期");
    }

    @Test
    void lastRoundBreachesBeforeLimitButClearingDefendersOnLastRoundFails() {
        var full = wall(10, false);
        var lastHp = new ConquestWallState(full.level(), full.maxHp(), BigDecimal.ONE.setScale(6), full.defense(), full.phase(), full.rulesVersion());
        var win = round(Map.of("special", 5000), Map.of(), Map.of("special", 1000), 1000, 35, 1, MAX_TECH, Map.of(), lastHp);
        assertTrue(win.attackerWin());
        var fail = round(Map.of("special", 5000), Map.of("infantry", 1), Map.of("special", 1000), 1000, 35, 1, MAX_TECH, Map.of(), wall(10, true));
        assertTrue(fail.finished()); assertFalse(fail.attackerWin());
        assertEquals("WALL_NOT_BREACHED", fail.terminalReason());
        assertTrue(fail.defenderArmy().isEmpty());
        var emptyFail = round(Map.of("special", 1), Map.of(), Map.of("special", 1000), 1000, 35, 1, Map.of(), Map.of(), full);
        assertEquals("WALL_NOT_BREACHED", emptyFail.terminalReason());
        assertTrue(emptyFail.wall().standing());
    }

    @Test
    void noWallAndOldSessionsWinButAttackersMustSurvive() {
        assertTrue(round(Map.of("special", 5000), Map.of(), Map.of("special", 0), 1000, 1, 0, MAX_TECH, Map.of(), wall(0, false)).attackerWin());
        assertTrue(round(Map.of("special", 5000), Map.of(), Map.of("special", 0), 1000, 1, 0, MAX_TECH, Map.of(), null).attackerWin());
        assertFalse(round(Map.of(), Map.of(), Map.of(), 1000, 1, 0, MAX_TECH, Map.of(), wall(0, false)).attackerWin());
    }

    @Test
    void plunderAndNpcNeverAcquireWallAndPracticeTracksCurrentCombatRules() {
        for (boolean player : new boolean[]{false, true}) {
            var result = engine.startWorldDispatch(Map.of("special", 5000), Map.of(), Map.of(), MAX_TECH,
                    Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, player ? "plunder" : "conquer", Map.of(), 0, player,
                    Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, wall(10, false));
            assertTrue(result.isWin()); assertNull(result.getWall());
        }
        assertNotEquals("power-v1-pvp-v1-5133573b966b3da879f5d633", PvpPowerModel.VERSION);
        assertEquals(BattleRules.VERSION, BattleRules.PRACTICE_VERSION);
        assertEquals("fort", BattleRules.domain("wall"));
    }
}
