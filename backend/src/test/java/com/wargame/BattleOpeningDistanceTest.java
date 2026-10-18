package com.wargame;

import com.wargame.model.constants.BattleScene;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.ConquestWallState;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证场景距离、实际接敌节奏及自动/逐回合一致性，不再固定三回合安全窗口。 */
class BattleOpeningDistanceTest {
    private final BattleService engine = new BattleService(0);

    @ParameterizedTest
    @CsvSource({"PLAYER,1850", "NPC,1000", "WILD,500"})
    void sceneAddsBufferToSingleLongestEffectiveRange(BattleScene scene, int buffer) {
        var attacker = new BattleService.TechCtx(Map.of("weapon_range", 10), Map.of(), 0);
        var defender = new BattleService.TechCtx(Map.of("weapon_range", 5), Map.of(), 0);
        assertEquals(2400 + buffer, engine.calcInitialDistance(Map.of("rocket", 1, "fighter", 1),
                Map.of("carrier", 1, "howitzer", 1), attacker, defender, null, scene));
        assertEquals(2775 + buffer, engine.calcInitialDistance(Map.of("infantry", 1),
                Map.of("howitzer", 1), null, attacker, null, scene));
    }

    @Test
    void deadUnknownAndMissingUnitsDoNotSetRangeButEmptyCityKeepsAttackerRange() {
        Map<String, Integer> army = new HashMap<>(Map.of("infantry", 1, "carrier", 0, "rocket", -1, "unknown", 1));
        army.put("howitzer", null);
        assertEquals(2010, engine.calcInitialDistance(army, Map.of(), null, null));
        assertEquals(1850, engine.calcInitialDistance(null, null, null, null));
        var tech = new BattleService.TechCtx(Map.of("weapon_range", 10), Map.of(), 0);
        assertEquals(2120, engine.calcInitialDistance(Map.of("special", 1), Map.of(), tech, null,
                ConquestWallState.create(10, false, "test")));
        assertEquals(3450, engine.calcInitialDistance(Map.of("rocket~support", 1), Map.of(), null, null));
    }

    @Test
    void enginesEquipmentSkillsAndChiefKnowledgeDoNotChangeOpeningDistance() {
        var plain = new BattleService.TechCtx(Map.of(), Map.of(), 0);
        var boosted = new BattleService.TechCtx(Map.of("arm_engine", 10, "@ue:infantry:speed", 15),
                Map.of("blitz", 5), 0, 0, new CommandBonus(1L, "司令", 0, 0, 0, 219));
        assertEquals(engine.calcInitialDistance(Map.of("infantry", 100), Map.of("infantry", 100), plain, plain),
                engine.calcInitialDistance(Map.of("infantry", 100), Map.of("infantry", 100), boosted, boosted));
    }

    @ParameterizedTest
    @CsvSource({"PLAYER,infantry,0,10", "NPC,infantry,0,5", "WILD,infantry,0,3",
            "PLAYER,infantry,10,7", "NPC,infantry,10,4", "WILD,infantry,10,2",
            "PLAYER,fighter,10,2", "NPC,fighter,10,1", "WILD,fighter,10,1"})
    void engagementDependsOnActualMovement(BattleScene scene, String unit, int level, int expectedRound) {
        var tech = Map.of("arm_engine", level, "air_engine", level, "weapon_range", level);
        var ctx = new BattleService.TechCtx(tech, Map.of("blitz", 5), 0);
        Map<String, Integer> army = Map.of(unit, 100);
        int distance = engine.calcInitialDistance(army, army, ctx, ctx, null, scene);
        var a = army; var b = army; var ap = Map.of(unit, 0); var bp = Map.of(unit, distance);
        for (int round = 1; round <= expectedRound; round++) {
            var state = engine.resolveWorldRound(a, b, ap, bp, distance, tech, tech, ctx.skills(), ctx.skills(),
                    0, 0, 0, 0, round, 0, Map.of(), Map.of());
            assertEquals(round == expectedRound ? expectedRound : 0, state.firstCombatRound(), state.log());
            a = state.attackerArmy(); b = state.defenderArmy(); ap = state.attackerPositions(); bp = state.defenderPositions();
        }
    }

    @ParameterizedTest
    @EnumSource(BattleScene.class)
    void automaticAndManualWorldBattlesUseSameDistanceAndFirstCombat(BattleScene scene) {
        Map<String, Integer> army = Map.of("infantry", 100);
        String report = engine.startWorldDispatch(army, army, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, "conquer", Map.of(), 0, true, Map.of(), Map.of(),
                CommandBonus.NONE, CommandBonus.NONE, null, scene).getReport();
        int distance = engine.calcInitialDistance(army, army, null, null, null, scene);
        assertTrue(report.contains("战场初始距离: " + distance), report);
        assertEquals((int) Math.ceil(scene.buffer() / 200.0), engine.firstCombatRoundFromLog(report), report);
        if (scene == BattleScene.WILD) {
            String wild = new BattleService(0).resolveWild(army, army).getReport();
            assertEquals(engine.firstCombatRoundFromLog(report), engine.firstCombatRoundFromLog(wild));
            assertTrue(wild.contains("战场初始距离: " + distance));
        }
    }
}
