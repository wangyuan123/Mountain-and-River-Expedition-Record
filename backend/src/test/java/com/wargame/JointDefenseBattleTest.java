package com.wargame;

import com.wargame.model.dto.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定输入与战斗种子，覆盖协防来源隔离、周期、同批伤害、概率和权限契约。 */
class JointDefenseBattleTest {
    final BattleService engine = new BattleService(17);
    JointDefenseState state(Map<String, Integer> a, Map<String, Integer> home, Map<String, Integer> support) {
        JointDefenseState state = new JointDefenseState(); state.setSeed(17);
        add(state, "attack", "attack", a); add(state, "0", "home", home); add(state, "1", "support", support);
        return state;
    }
    void add(JointDefenseState state, String id, String kind, Map<String, Integer> army) {
        var f = new JointDefenseState.Formation(); f.setId(id); f.setKind(kind); f.setInitial(new LinkedHashMap<>(army));
        f.setOfficerId(1L); f.setPlayerName("团友甲"); f.setOfficerName("李某"); state.getFormations().put(id, f);
    }
    BattleRoundState round(JointDefenseState state, Map<String, Integer> a, Map<String, Integer> d,
            Map<String, Integer> ap, Map<String, Integer> dp, int round) {
        Map<String, BattleService.UnitOrder> hold = new HashMap<>();
        a.keySet().forEach(id -> hold.put(id, new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));
        d.keySet().forEach(id -> hold.put(id, new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));
        return engine.resolveJointRound(a, d, ap, dp, 5000, new BattleService.TechCtx(Map.of(), Map.of(), 0),
                new BattleService.TechCtx(Map.of(), Map.of(), 999), round, hold, hold, "conquer", null, state);
    }
    @Test void sourceOfficersAreIsolatedAndNoOfficerDoesNotBorrowHomeCommander() {
        Map<String, Integer> a = Map.of("infantry", 1000000), home = Map.of("htank", 10000), support = Map.of("htank~s1", 10000);
        var state = state(a, home, support); state.getFormations().get("0").setMilitary(100);
        var result = round(state, a, Map.of("htank", 10000, "htank~s1", 10000), Map.of("infantry", 0), Map.of("htank", 0, "htank~s1", 0), 1);
        double homeDamage = state.getFormations().get("0").getDamage().get("htank"), allyDamage = state.getFormations().get("1").getDamage().get("htank~s1");
        assertEquals(2, homeDamage / allyDamage, 1e-8);
        assertTrue(result.log().contains("【援军·团友甲·将领李某】"), result.log());
        assertEquals(1, state.getFormations().get("0").getFirstCombatRound());
    }
    @Test void bonusLogsFollowEachFormationCombatWindowWithoutChangingDamage() {
        var a = Map.of("infantry", 1000000);
        var d = Map.of("htank", 10000, "htank~s1", 10000);
        var state = state(a, Map.of("htank", 10000), Map.of("htank~s1", 10000));
        state.getFormations().get("0").setMilitary(100);
        state.getFormations().get("0").setDefense(80);
        state.getFormations().get("0").getSkills().put("frenzy", 5);
        var first = round(state, a, d, Map.of("infantry", 0), Map.of("htank", 0, "htank~s1", 4000), 5);
        assertTrue(first.log().contains("敌方将领加成：【守方·守将李某】军事属性 +100%攻击；防御属性 +80%防御；全军冲锋 +50%攻击"), first.log());
        assertTrue(first.log().contains("敌方将领加成：【援军·团友甲·将领李某】尚未接战"), first.log());
        var next = round(state, first.attackerArmy(), first.defenderArmy(), first.attackerPositions(), first.defenderPositions(), 6);
        assertTrue(next.log().contains("敌方将领加成：【守方·守将李某】本回合无将领属性或技能加成生效"), next.log());
        assertTrue(state.getEvents().stream().anyMatch(e -> "COMMANDER_BONUS".equals(e.get("type")) && Integer.valueOf(5).equals(e.get("round"))));
    }
    @Test void distantSourceDoesNotConsumeCycleAndSameMarchSharesOneCycle() {
        Map<String, Integer> a = Map.of("infantry", 1000000), home = Map.of("rocket", 10), support = Map.of("infantry~s1", 20, "htank~s1", 20);
        var state = state(a, home, support);
        var result = round(state, a, Map.of("rocket", 10, "infantry~s1", 20, "htank~s1", 20), Map.of("infantry", 0),
                Map.of("rocket", 1000, "infantry~s1", 4000, "htank~s1", 4000), 3);
        assertEquals(3, state.getFormations().get("0").getFirstCombatRound());
        assertEquals(0, state.getFormations().get("1").getFirstCombatRound());
        var persisted = JointDefenseState.read(JsonUtil.toJson(state));
        round(persisted, result.attackerArmy(), result.defenderArmy(), result.attackerPositions(), Map.of("rocket", 1000, "infantry~s1", 0, "htank~s1", 0), 5);
        assertEquals(5, persisted.getFormations().get("1").getFirstCombatRound());
    }
    @Test void rearSameUnitDoesNotShareDamageAndCounterQuotaIsFinite() {
        var state = state(Map.of("htank", 1000, "infantry", 1000), Map.of("infantry", 1000000), Map.of("infantry~s1", 1000000));
        state.getFormations().get("0").getSkills().put("counter", 5);
        var result = round(state, Map.of("htank", 1000, "infantry", 1000), Map.of("infantry", 1000000, "infantry~s1", 1000000),
                Map.of("htank", 0, "infantry", 0), Map.of("infantry", 0, "infantry~s1", 4000), 1);
        assertEquals(1000000, result.defenderArmy().get("infantry~s1"));
        assertEquals(Set.of("1:infantry"), state.getFormations().get("0").getCounterUsed());
        assertTrue(result.attackerArmy().isEmpty(), result.log());
    }
    @Test void sourceOrderAndRestartDoNotChangeResultOrRandomKills() {
        var a = Map.of("infantry", 11); var d = new LinkedHashMap<String, Integer>(); d.put("infantry", 1000); d.put("infantry~s1", 1000);
        var state = state(a, Map.of("infantry", 1000), Map.of("infantry~s1", 1000));
        String initial = JsonUtil.toJson(state);
        var left = round(state, a, d, Map.of("infantry", 0), Map.of("infantry", 0, "infantry~s1", 0), 1);
        var reverse = new LinkedHashMap<String, Integer>(); reverse.put("infantry~s1", 1000); reverse.put("infantry", 1000);
        var right = round(JointDefenseState.read(initial), a, reverse, Map.of("infantry", 0), Map.of("infantry", 0, "infantry~s1", 0), 1);
        assertEquals(left.attackerArmy(), right.attackerArmy()); assertEquals(left.defenderArmy(), right.defenderArmy());
    }
    @Test void contributionsConserveTroopsAndPublicProjectionRejectsInternalFields() {
        var state = state(Map.of("infantry", 10), Map.of("htank", 30), Map.of("htank~s1", 20));
        var rows = state.contributions(Map.of("infantry", 5), Map.of("htank", 12, "htank~s1", 18));
        for (var row : rows) assertEquals((int) row.get("initial"), (int) row.get("losses") + (int) row.get("survivors"));
        var publicData = JointBattleProjection.publicReport(Map.of("formationContributions", rows, "jointDefense", state, "sourceCityId", 555, "officers", List.of(1), "fromCoord", "10,10"));
        assertEquals(Set.of("formationContributions"), publicData.keySet());
    }
    @Test void manyMillionTroopSourcesCompleteWithoutDroppingFormation() {
        Map<String, Integer> a = Map.of("htank", 1000000), defenders = new LinkedHashMap<>();
        var state = state(a, Map.of(), Map.of()); state.getFormations().remove("1");
        for (int i = 1; i <= 64; i++) { String unit = "htank~s" + i; defenders.put(unit, 1000000); add(state, String.valueOf(i), "support", Map.of(unit, 1000000)); }
        Map<String, Integer> positions = new LinkedHashMap<>(); defenders.keySet().forEach(id -> positions.put(id, 0));
        long started = System.nanoTime(); var result = round(state, a, defenders, Map.of("htank", 0), positions, 1);
        assertEquals(64, result.defenderArmy().size()); assertEquals(65, state.contributions(result.attackerArmy(), result.defenderArmy()).size());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10000);
        System.out.println("JointDefense scale: 64 formations x 1000000 htank, roundMs=" + ((System.nanoTime() - started) / 1_000_000));
    }

    @Test void identicalTroopsSplitAcrossSourcesKeepTheSameTotalDamageAndProbabilisticKills() {
        var a = Map.of("infantry", 12345);
        var whole = state(a, Map.of("infantry", 20000), Map.of());
        var split = state(a, Map.of("infantry", 10000), Map.of("infantry~s1", 10000));
        var single = round(whole, a, Map.of("infantry", 20000), Map.of("infantry", 0), Map.of("infantry", 0), 1);
        var divided = round(split, a, Map.of("infantry", 10000, "infantry~s1", 10000), Map.of("infantry", 0), Map.of("infantry", 0, "infantry~s1", 0), 1);
        assertEquals(single.attackerArmy(), divided.attackerArmy());
        assertEquals(single.defenderArmy().values().stream().mapToInt(Integer::intValue).sum(), divided.defenderArmy().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(whole.getFormations().get("attack").getDamage().get("infantry"), split.getFormations().get("attack").getDamage().get("infantry"), 1e-8);
    }

    @Test void uniformMovementReadsBaseCommandForAllSourcesAndFirstBlitzDoesNotMoveRetroactively() {
        var a = Map.of("infantry", 10000);
        var d = Map.of("htank", 100, "htank~s1", 100);
        var state = state(a, Map.of("htank", 100), Map.of("htank~s1", 100));
        state.getFormations().get("1").getSkills().put("blitz", 5);
        var result = engine.resolveJointRound(a, d, Map.of("infantry", 0), Map.of("htank", 200, "htank~s1", 200), 5000,
                new BattleService.TechCtx(Map.of(), Map.of(), 0), new BattleService.TechCtx(Map.of(), Map.of(), 0), 1,
                Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)),
                Map.of("htank", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)), "plunder", null, state);
        assertEquals(200, result.defenderPositions().get("htank"));
        assertEquals(200, result.defenderPositions().get("htank~s1"));
        assertEquals(1, state.getFormations().get("1").getFirstCombatRound());
        assertTrue(state.getEvents().stream().filter(e -> "FIRE".equals(e.get("type"))).allMatch(e -> e.containsKey("formationId")));
    }

    @Test void sharedReportAlsoRejectsSensitiveFieldsInsideEventsAndContributions() {
        var report = JointBattleProjection.publicReport(Map.of("formationEvents", List.of(Map.of("type", "FIRE", "damage", 15,
                "officerId", 77, "citySlot", 3, "equipment", Map.of("tank", 1))),
                "formationContributions", List.of(Map.of("formationId", "formation-2", "initial", 10, "sourceCityId", 333))));
        assertEquals(List.of(Map.of("type", "FIRE", "damage", 15)), report.get("formationEvents"));
        assertEquals(List.of(Map.of("formationId", "formation-2", "initial", 10)), report.get("formationContributions"));
        assertFalse(JointBattleProjection.publicReport(Map.of("formationRecovery", List.of(Map.of("medicalLevel", 10, "cityId", 99)))).containsKey("formationRecovery"));
    }

    @Test void collaborativeAttackHasOneLossEventPerTargetAndIndependentDamageContributions() {
        var a = Map.of("infantry", 1000000);
        var d = Map.of("htank", 10000, "htank~s1", 10000);
        var state = state(a, Map.of("htank", 10000), Map.of("htank~s1", 10000));
        round(state, a, d, Map.of("infantry", 0), Map.of("htank", 0, "htank~s1", 0), 1);
        var hits = state.getEvents().stream().filter(e -> "FIRE".equals(e.get("type")) && "infantry".equals(e.get("targetUnit"))).toList();
        assertEquals(2, hits.size()); assertEquals(hits.get(0).get("batch"), hits.get(1).get("batch"));
        assertTrue(hits.stream().noneMatch(e -> e.containsKey("targetLosses")));
        assertEquals(1, state.getEvents().stream().filter(e -> "LOSSES".equals(e.get("type")) && hits.get(0).get("batch").equals(e.get("batch"))
                && "infantry".equals(e.get("unit"))).count());
    }

    @Test void outOfRangeWallCandidateDoesNotStartSkillCycle() {
        var attackers = Map.of("infantry", 100);
        var state = state(attackers, Map.of(), Map.of());
        state.getFormations().get("attack").getSkills().put("blitz", 5);
        var wall = ConquestWallState.create(10, 0, false, JointDefenseState.VERSION);
        var result = engine.resolveJointRound(attackers, Map.of(), Map.of("infantry", 0), Map.of(), 5000,
                new BattleService.TechCtx(Map.of(), Map.of(), 0), new BattleService.TechCtx(Map.of(), Map.of(), 0), 1,
                Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)), Map.of(), "conquer", wall, state);
        assertEquals(0, state.getFormations().get("attack").getFirstCombatRound());
        assertEquals(wall.hpRemaining(), result.wall().hpRemaining());
        assertTrue(state.getEvents().stream().noneMatch(e -> "ENGAGE".equals(e.get("type")) || "WALL".equals(e.get("type"))));
    }

    @Test void seededProbabilityReplaysAcrossDifferentJvmObjectHashHistories() throws Exception {
        String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String[] outputs = new String[2];
        for (int i = 0; i < 2; i++) {
            Process process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"), ReplayWorker.class.getName(), String.valueOf(i * 1000))
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
            outputs[i] = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), outputs[i]);
        }
        assertEquals(outputs[0], outputs[1]);
    }

    /** 独立进程先改变对象哈希历史，再重放固定战斗，覆盖服务重启而非同一实例内的重试。 */
    public static class ReplayWorker {
        public static void main(String[] args) {
            for (int i = 0; i < Integer.parseInt(args[0]); i++) System.identityHashCode(new Object());
            var fixture = new JointDefenseBattleTest();
            var a = Map.of("infantry", 11); var d = Map.of("infantry", 1000, "infantry~s1", 1000);
            for (int seed = 1; seed <= 24; seed++) {
                var state = fixture.state(a, Map.of("infantry", 1000), Map.of("infantry~s1", 1000)); state.setSeed(seed);
                var result = fixture.round(state, a, d, Map.of("infantry", 0), Map.of("infantry", 0, "infantry~s1", 0), 1);
                System.out.println(JsonUtil.toJson(new TreeMap<>(result.defenderArmy())));
            }
        }
    }
}
