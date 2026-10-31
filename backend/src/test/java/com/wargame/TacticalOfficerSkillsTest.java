package com.wargame;

import com.wargame.model.constants.*;
import com.wargame.model.dto.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定种子验证新技能在真实交火中的伤害、周期、范围与跨回合快照。 */
class TacticalOfficerSkillsTest {
    private static final Map<String, BattleService.UnitOrder> HOLD = Map.of(
            "infantry", new BattleService.UnitOrder(BattleService.CommandAction.HOLD),
            "armored", new BattleService.UnitOrder(BattleService.CommandAction.HOLD),
            "rocket", new BattleService.UnitOrder(BattleService.CommandAction.HOLD));
    private BattleService.TechCtx ctx(Map<String, Integer> skills, BattleSkillState.Army memory) {
        return new BattleService.TechCtx(Map.of(), skills, 0, 0).withMemory(memory);
    }
    private BattleRoundState round(BattleService engine, BattleSkillState memory, Map<String, Integer> a, Map<String, Integer> b,
            Map<String, Integer> as, Map<String, Integer> bs, int round, int first) {
        Map<String, Integer> ap = new LinkedHashMap<>(), bp = new LinkedHashMap<>();
        a.keySet().forEach(id -> ap.put(id, 0)); b.keySet().forEach(id -> bp.put(id, 100));
        return engine.resolvePracticeRound(a, b, ap, bp, 500, ctx(as, memory.getAttacker()), ctx(bs, memory.getDefender()),
                round, first, HOLD, HOLD);
    }
    private double fire(BattleService engine, String side, String unit) {
        return engine.practiceEvents().stream().filter(e -> "FIRE".equals(e.get("type")) && side.equals(e.get("side"))
                && unit.equals(e.get("unit"))).mapToDouble(e -> ((Number) e.get("damage")).doubleValue()).sum();
    }

    @Test void thirdConsecutiveRoundSurvivesSerializationAndCounterDoesNotAdvanceIt() {
        BattleSkillState memory = new BattleSkillState();
        Map<String, Integer> a = Map.of("infantry", 10000), b = Map.of("infantry", 10000);
        for (int r = 1; r <= 3; r++) {
            BattleService engine = new BattleService(101);
            var result = round(engine, memory, a, b, Map.of("ranging", 5), Map.of("counter", 1), r, 1);
            assertEquals(r == 3, engine.practiceEvents().stream().anyMatch(e -> "SKILL_EFFECT".equals(e.get("type")) && "ranging".equals(e.get("skill"))));
            a = result.attackerArmy(); b = result.defenderArmy();
            memory = BattleSkillState.read(JsonUtil.toJson(memory));
        }
        assertEquals(0, memory.getAttacker().unit("infantry").getFocusCount());
        BattleSkillEffects.begin(memory.getAttacker(), 5, a, a, Map.of());
        BattleSkillEffects.aim(memory.getAttacker(), "infantry", memory.getDefender(), "infantry", Map.of("ranging", 5));
        assertEquals(1, memory.getAttacker().unit("infantry").getFocusCount(), "中断攻击后重置连续校射");
    }

    @Test void barrierAbsorbsFirstHitAndDoesNotPersistIntoTheOffRound() {
        BattleSkillState protectedMemory = new BattleSkillState();
        BattleService shield = new BattleService(10), base = new BattleService(10);
        var a = Map.of("infantry", 10000); var b = Map.of("infantry", 10000);
        var result = round(shield, protectedMemory, a, b, Map.of(), Map.of("barrier", 5), 1, 0);
        round(base, new BattleSkillState(), a, b, Map.of(), Map.of(), 1, 0);
        assertTrue(fire(shield, "A", "infantry") < fire(base, "A", "infantry"));
        assertEquals(120000, protectedMemory.getDefender().getEvents().stream()
                .filter(e -> "barrier".equals(e.get("skill")) && ((Number)e.get("value")).doubleValue() > 0)
                .mapToDouble(e -> ((Number)e.get("value")).doubleValue()).sum(), 1e-8);
        round(shield, protectedMemory, result.attackerArmy(), result.defenderArmy(), Map.of(), Map.of("barrier", 5), 2, 1);
        assertEquals(0, protectedMemory.getDefender().unit("infantry").getBarrier());
    }

    @Test void lastStandUsesOpeningCountsAndExecutionUsesOriginalTargetCounts() {
        var initial = Map.of("infantry", 10000);
        BattleSkillState memory = new BattleSkillState();
        memory.getAttacker().setInitial(initial); memory.getDefender().setInitial(initial);
        BattleService boosted = new BattleService(4), plain = new BattleService(4);
        round(boosted, memory, Map.of("infantry", 4000), Map.of("infantry", 2000),
                Map.of("last_stand", 5, "execution", 5), Map.of(), 2, 1);
        BattleSkillState baseline = new BattleSkillState(); baseline.getAttacker().setInitial(initial); baseline.getDefender().setInitial(initial);
        round(plain, baseline, Map.of("infantry", 4000), Map.of("infantry", 2000), Map.of(), Map.of(), 2, 1);
        assertEquals(1.5, fire(boosted, "A", "infantry") / fire(plain, "A", "infantry"), 1e-8);
        assertEquals(10000, memory.getAttacker().getInitial().get("infantry"));
    }

    @Test void reprisalConsumesOnlyPreviousTwoRoundsOfUnshieldedDamageAndCapsItsBonus() {
        BattleSkillState memory = new BattleSkillState();
        BattleSkillEffects.begin(memory.getAttacker(), 2, Map.of("infantry", 100), Map.of("infantry", 100), Map.of());
        assertEquals(0, BattleSkillEffects.receive(memory.getAttacker(), "infantry", Map.of("barrier", 5), true, 120, 100, 1000));
        BattleSkillEffects.begin(memory.getAttacker(), 3, Map.of("infantry", 100), Map.of(), Map.of());
        BattleSkillEffects.receive(memory.getAttacker(), "infantry", Map.of(), false, 120, 100, 5000);
        BattleSkillEffects.begin(memory.getAttacker(), 4, Map.of("infantry", 100), Map.of(), Map.of());
        BattleSkillEffects.receive(memory.getAttacker(), "infantry", Map.of(), false, 120, 100, 2000);
        assertEquals(40, BattleSkillEffects.reprisal(memory.getAttacker(), "infantry", "infantry", Map.of("reprisal", 5), true, 100));
        assertEquals(0, BattleSkillEffects.reprisal(memory.getAttacker(), "infantry", "infantry", Map.of("reprisal", 5), true, 100));
        assertEquals(Map.of(4, 2000.0), memory.getAttacker().unit("infantry").getReceived());
        BattleService engine = new BattleService(8);
        BattleSkillState actual = new BattleSkillState();
        BattleSkillEffects.begin(actual.getAttacker(), 3, Map.of("infantry", 10000), Map.of("infantry", 10000), Map.of());
        actual.getAttacker().unit("infantry").getReceived().put(3, 100000.0);
        round(engine, actual, Map.of("infantry", 10000), Map.of("infantry", 10000), Map.of("reprisal", 5), Map.of(), 4, 1);
        assertTrue(engine.practiceEvents().stream().anyMatch(e -> "reprisal".equals(e.get("skill")) && "SKILL_EFFECT".equals(e.get("type"))));
    }

    @Test void smokeSlowsOnlyNextMovementAndDoesNotAlterTheInitiativeSpeed() {
        BattleSkillState memory = new BattleSkillState();
        BattleService engine = new BattleService(1);
        var first = round(engine, memory, Map.of("infantry", 10000), Map.of("infantry", 10000), Map.of("smoke", 5), Map.of(), 1, 0);
        assertEquals(2, memory.getDefender().unit("infantry").getSmokeRound());
        Map<String, BattleService.UnitOrder> move = Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.RETREAT));
        var second = engine.resolvePracticeRound(first.attackerArmy(), first.defenderArmy(), Map.of("infantry", 300), Map.of("infantry", 500), 1000,
                ctx(Map.of("smoke", 5), memory.getAttacker()), ctx(Map.of(), memory.getDefender()), 2, 1, HOLD, move);
        assertEquals(575, second.defenderPositions().get("infantry"));
        assertEquals(2.0, engine.practiceEvents().stream().filter(e -> "INITIATIVE".equals(e.get("type")) && "B".equals(e.get("side")))
                .findFirst().map(e -> ((Number)e.get("speed")).doubleValue()).orElseThrow());
        BattleSkillEffects.begin(memory.getDefender(), 3, first.defenderArmy(), Map.of(), Map.of());
        assertEquals(1, BattleSkillEffects.movement(memory.getDefender(), "infantry", Map.of()));
    }

    @Test void ricochetHasOneIndependentLegalTargetAndNeverChains() {
        BattleService engine = new BattleService(2);
        round(engine, new BattleSkillState(), Map.of("infantry", 10000),
                Map.of("infantry", 10000, "armored", 10000, "rocket", 10000), Map.of("ricochet", 5), Map.of(), 1, 0);
        var events = engine.practiceEvents().stream().filter(e -> "RICOCHET".equals(e.get("type"))).toList();
        assertEquals(1, events.size());
        assertEquals("armored", events.get(0).get("target"));
        assertTrue(((Number)events.get(0).get("damage")).doubleValue() > 0);
        BattleService noSecond = new BattleService(2);
        round(noSecond, new BattleSkillState(), Map.of("infantry", 10000), Map.of("infantry", 10000), Map.of("ricochet", 5), Map.of(), 1, 0);
        assertTrue(noSecond.practiceEvents().stream().noneMatch(e -> "RICOCHET".equals(e.get("type"))));
    }

    @Test void pursuitRequiresMeaningfulTargetAndAppliesOnlyOnTheNextRound() {
        BattleSkillState memory = new BattleSkillState();
        BattleSkillEffects.begin(memory.getAttacker(), 1, Map.of("infantry", 100), Map.of("infantry", 100), Map.of());
        BattleSkillEffects.begin(memory.getDefender(), 1, Map.of("infantry", 1, "armored", 100), Map.of("infantry", 1, "armored", 100), Map.of());
        BattleSkillEffects.defeated(memory.getAttacker(), "infantry", memory.getDefender(), "infantry", Map.of("pursuit", 5));
        assertEquals(0, memory.getAttacker().unit("infantry").getPursuitRound());
        BattleSkillEffects.defeated(memory.getAttacker(), "infantry", memory.getDefender(), "armored", Map.of("pursuit", 5));
        BattleSkillEffects.begin(memory.getAttacker(), 2, Map.of("infantry", 100), Map.of(), Map.of());
        assertEquals(1.3, BattleSkillEffects.movement(memory.getAttacker(), "infantry", Map.of("pursuit", 5)), 1e-8);
        BattleSkillEffects.aim(memory.getAttacker(), "infantry", memory.getDefender(), "armored", Map.of("pursuit", 5));
        assertEquals(115, BattleSkillEffects.outgoing(memory.getAttacker(), "infantry", memory.getDefender(), "armored", Map.of("armored", 100), Map.of("pursuit", 5), true, 100), 1e-8);
        BattleSkillEffects.begin(memory.getAttacker(), 3, Map.of("infantry", 100), Map.of(), Map.of());
        assertEquals(1, BattleSkillEffects.movement(memory.getAttacker(), "infantry", Map.of("pursuit", 5)));
    }

    @Test void pursuitReportHasSeparateMovementAndDamageRates() {
        String description = OfficerSkillDef.getSkill("pursuit").descriptionAtLevel(3);
        assertTrue(description.contains("移动速度+18%"));
        assertTrue(description.contains("伤害+9%"));
    }

    @Test void jointDefenseKeepsSupportSkillsAndTheirProgressAfterReload() {
        var fixture = new JointDefenseBattleTest();
        Map<String, Integer> a = Map.of("infantry", 100000);
        Map<String, Integer> d = Map.of("infantry", 100000, "infantry~s1", 100000);
        var state = fixture.state(a, Map.of("infantry", 100000), Map.of("infantry~s1", 100000));
        state.getFormations().get("1").getSkills().putAll(Map.of("ranging", 5, "barrier", 5));
        Map<String, Integer> ap = Map.of("infantry", 0), dp = Map.of("infantry", 100, "infantry~s1", 100);
        for (int r = 1; r <= 3; r++) {
            var result = fixture.round(state, a, d, ap, dp, r);
            var events = state.getSkillState().getDefender().getEvents();
            assertTrue(events.stream().filter(e -> "barrier".equals(e.get("skill")))
                    .allMatch(e -> "infantry~s1".equals(e.get("unit"))), "援军屏障不能作用于主城部队");
            assertEquals(r == 3, events.stream().anyMatch(e -> "ranging".equals(e.get("skill"))));
            assertTrue(events.stream().filter(e -> "ranging".equals(e.get("skill")))
                    .allMatch(e -> "infantry~s1".equals(e.get("unit"))));
            if (r == 1) {
                double generated = events.stream().filter(e -> "barrier".equals(e.get("skill")) && ((Number)e.get("value")).doubleValue() > 0)
                        .mapToDouble(e -> ((Number)e.get("value")).doubleValue()).sum();
                assertEquals(1200000, generated, 1e-8);
            }
            a = result.attackerArmy(); d = result.defenderArmy();
            ap = result.attackerPositions(); dp = result.defenderPositions();
            state = JointDefenseState.read(JsonUtil.toJson(state));
        }
        assertEquals(100000, state.getSkillState().getDefender().getInitial().get("infantry~s1"));
        assertEquals("1", state.getSkillState().getDefender().getSources().get("infantry~s1"));
    }

    @Test void casualtyThresholdsDoNotBorrowCountsFromOtherSourcesOrProtectForts() {
        var state = new BattleSkillState.Army();
        BattleSkillEffects.begin(state, 1, Map.of("infantry", 20, "infantry~s1", 1000),
                Map.of("infantry", 100, "infantry~s1", 1000), Map.of("infantry", "home", "infantry~s1", "support"));
        assertEquals(1.2, BattleSkillEffects.ownAttack(state, "infantry", Map.of("last_stand", 5)), 1e-8);
        assertEquals(1, BattleSkillEffects.ownAttack(state, "infantry~s1", Map.of("last_stand", 5)), 1e-8);
        var forts = new BattleSkillState.Army();
        BattleSkillEffects.begin(forts, 1, Map.of("bunker", 10), Map.of("bunker", 100), Map.of());
        assertEquals(0, BattleSkillEffects.shield(forts, "bunker", Map.of("barrier", 5), true, 1000));
        assertEquals(1, BattleSkillEffects.ownAttack(forts, "bunker", Map.of("last_stand", 5)));
    }
}
