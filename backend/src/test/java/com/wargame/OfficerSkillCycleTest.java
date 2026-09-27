package com.wargame;

import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 从实际交火、伤害和战报验证技能周期，避免只改显示文案或只调整某一方的效果。 */
class OfficerSkillCycleTest {
    private static final Map<String, Integer> ARMY = Map.of("infantry", 10_000);
    private static final Map<String, Integer> TECH = Map.of("cmd_hp", 100);
    private static final Map<String, BattleService.UnitOrder> HOLD = orders(BattleService.CommandAction.HOLD);

    private static Map<String, BattleService.UnitOrder> orders(BattleService.CommandAction action) {
        return Map.of("infantry", new BattleService.UnitOrder(action));
    }

    private BattleRoundState resolve(int round, int firstCombatRound,
                                     Map<String, Integer> mine, Map<String, Integer> enemy,
                                     Map<String, Integer> minePos, Map<String, Integer> enemyPos,
                                     Map<String, Integer> skills, Map<String, BattleService.UnitOrder> commands) {
        // 每次重建服务，以验证周期只依赖传入快照，不依赖 JVM 中的临时状态。
        return new BattleService(0).resolveWorldRound(mine, enemy, minePos, enemyPos, 400,
                TECH, TECH, skills, skills, 0, 0, 0, 0, 0, 0,
                round, firstCombatRound, commands, commands);
    }

    private long firstDamage(String log) {
        var match = Pattern.compile(" 伤害(\\d+)").matcher(log);
        assertTrue(match.find(), log);
        return Long.parseLong(match.group(1));
    }

    @Test
    void counterDamageScalesFromTwentyToOneHundredPercentOfSurvivingArmy() {
        long levelOneDamage = 0;
        for (int level = 1; level <= 5; level++) {
            var state = resolve(4, 4, ARMY, ARMY,
                    Map.of("infantry", 150), Map.of("infantry", 250), Map.of("counter", level), HOLD);
            var counter = Pattern.compile("\\[绝境反击\\].*? 伤害(\\d+)").matcher(state.log());
            assertTrue(counter.find(), state.log());
            long damage = Long.parseLong(counter.group(1));
            assertTrue(state.log().contains("绝境反击 " + (20 * level) + "%反击伤害"), state.log());
            if (level == 1) levelOneDamage = damage;
            else assertTrue(Math.abs(damage - levelOneDamage * level) <= 2,
                    "Lv." + level + " 应按受击后剩余兵力的同一基础伤害线性放大：" + state.log());
        }
    }

    @Test
    void fullLevelCounterUsesTroopsRemainingAfterTheIncomingHit() {
        int startingTroops = 2_000;
        var state = new BattleService(0).resolveWorldRound(
                Map.of("infantry", startingTroops), Map.of("infantry", startingTroops),
                Map.of("infantry", 150), Map.of("infantry", 250), 400,
                Map.of(), Map.of(), Map.of(), Map.of("counter", 5),
                0, 0, 0, 0, 0, 0, 4, 4, HOLD, HOLD);
        var counter = Pattern.compile("敌方[^\\n]*\\((\\d+)\\) \\[绝境反击\\].*? 伤害(\\d+)")
                .matcher(state.log());
        assertTrue(counter.find(), state.log());
        int survivingTroops = Integer.parseInt(counter.group(1));
        assertTrue(survivingTroops < startingTroops, state.log());
        var infantry = UnitDef.UNITS.get("infantry");
        long fullDamage = Math.round(survivingTroops * infantry.atkGround() * 100
                / (100 + 5 * infantry.def()));
        assertEquals(fullDamage, Long.parseLong(counter.group(2)), state.log());
    }

    @ParameterizedTest
    @ValueSource(strings = {"frenzy", "bulwark", "learn", "borrow_armor", "counter"})
    void allPeriodicSkillsStartAtRoundFourAndRepeatAtSevenTenThirteen(String skill) {
        Map<String, Integer> mine = ARMY;
        Map<String, Integer> enemy = ARMY;
        Map<String, Integer> minePos = Map.of("infantry", 0);
        Map<String, Integer> enemyPos = Map.of("infantry", 400);
        int firstCombatRound = 0;
        String name = OfficerSkillDef.getSkill(skill).name();

        for (int round = 1; round <= 13; round++) {
            var commands = round == 4 ? orders(BattleService.CommandAction.ADVANCE) : HOLD;
            var state = resolve(round, firstCombatRound, mine, enemy, minePos, enemyPos, Map.of(skill, 5), commands);
            var baseline = resolve(round, firstCombatRound, mine, enemy, minePos, enemyPos, Map.of(), commands);
            boolean active = round == 4 || round == 7 || round == 10 || round == 13;
            assertEquals(round >= 4 ? 4 : 0, state.firstCombatRound(), state.log());
            for (String side : new String[]{"我方", "敌方"}) {
                String bonus = state.log().lines().filter(line -> line.startsWith(side + "将领加成："))
                        .findFirst().orElseThrow();
                assertEquals(active, bonus.contains(name), "回合 " + round + ": " + bonus);
            }
            assertFalse(state.finished(), state.log());
            if (round < 4) {
                assertFalse(state.log().contains(" 伤害"), state.log());
            } else if ("counter".equals(skill)) {
                assertEquals(active, state.log().contains("[绝境反击]"), state.log());
                assertEquals(firstDamage(baseline.log()), firstDamage(state.log()));
            } else if (!active) {
                assertEquals(firstDamage(baseline.log()), firstDamage(state.log()), state.log());
            } else if ("frenzy".equals(skill) || "learn".equals(skill)) {
                assertTrue(firstDamage(state.log()) > firstDamage(baseline.log()), state.log());
            } else {
                assertTrue(firstDamage(state.log()) < firstDamage(baseline.log()), state.log());
            }
            mine = state.attackerArmy();
            enemy = state.defenderArmy();
            minePos = state.attackerPositions();
            enemyPos = state.defenderPositions();
            firstCombatRound = state.firstCombatRound();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 6})
    void skillCycleFollowsAnyFirstCombatRound(int firstRound) {
        int firstCombatRound = 0;
        for (int round = firstRound; round <= firstRound + 3; round++) {
            var state = resolve(round, firstCombatRound, ARMY, ARMY,
                    Map.of("infantry", 150), Map.of("infantry", 250), Map.of("frenzy", 5), HOLD);
            assertEquals(firstRound, state.firstCombatRound());
            assertEquals(round == firstRound || round == firstRound + 3, state.log().contains("全军冲锋"));
            firstCombatRound = state.firstCombatRound();
        }
    }

    @Test
    void retreatAndReengagementDoNotRestartSkillCycle() {
        var first = resolve(4, 0, ARMY, ARMY, Map.of("infantry", 0), Map.of("infantry", 400),
                Map.of("frenzy", 5), orders(BattleService.CommandAction.ADVANCE));
        var retreat = resolve(5, first.firstCombatRound(), first.attackerArmy(), first.defenderArmy(),
                first.attackerPositions(), first.defenderPositions(), Map.of("frenzy", 5),
                orders(BattleService.CommandAction.RETREAT));
        assertFalse(retreat.log().contains(" 伤害"), retreat.log());
        var reengage = resolve(6, retreat.firstCombatRound(), retreat.attackerArmy(), retreat.defenderArmy(),
                retreat.attackerPositions(), retreat.defenderPositions(), Map.of("frenzy", 5),
                orders(BattleService.CommandAction.ADVANCE));
        assertTrue(reengage.log().contains(" 伤害"), reengage.log());
        assertFalse(reengage.log().contains("全军冲锋"), reengage.log());
        var next = resolve(7, reengage.firstCombatRound(), reengage.attackerArmy(), reengage.defenderArmy(),
                reengage.attackerPositions(), reengage.defenderPositions(), Map.of("frenzy", 5), HOLD);
        assertEquals(4, next.firstCombatRound());
        assertTrue(next.log().contains("全军冲锋"), next.log());
    }

    @Test
    void firstShotByEitherSideStartsSharedCycleEvenIfTargetCannotFireBack() {
        var state = new BattleService(0).resolveWorldRound(
                Map.of("truck", 10_000), ARMY, Map.of("truck", 0), Map.of("infantry", 100), 400,
                TECH, TECH, Map.of("bulwark", 5, "counter", 5), Map.of("frenzy", 5),
                0, 0, 0, 0, 0, 0, 4, 0,
                Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)), HOLD);
        assertEquals(4, state.firstCombatRound());
        assertTrue(state.log().contains("我方将领加成：坚守阵地"), state.log());
        assertTrue(state.log().contains("敌方将领加成：全军冲锋"), state.log());
        assertTrue(state.log().contains(" 伤害"), state.log());
        assertFalse(state.log().contains("[绝境反击]"), "卡车射程不足时不能仅因技能回合而反击");
    }

    @Test
    void commanderAttributesKeepTheirIndependentTiming() {
        var state = new BattleService(0).resolveWorldRound(ARMY, ARMY,
                Map.of("infantry", 150), Map.of("infantry", 250), 400, TECH, TECH,
                Map.of("frenzy", 5), Map.of(), 50, 50, 0, 0, 0, 0, 6, 4, HOLD, HOLD);
        assertTrue(state.log().contains("军事属性 +50%攻击"));
        assertTrue(state.log().contains("防御属性 +50%防御"));
        assertFalse(state.log().contains("全军冲锋"));
    }

    @Test
    void fullBattleAndPersistedRoundsProduceTheSameSkillCycleAndDamage() {
        var skills = Map.of("frenzy", 5, "learn", 5, "counter", 5);
        var full = new BattleService(17).startWorldDispatch(ARMY, ARMY, Map.of(), TECH, TECH, skills, skills,
                0, 0, 0, 0, "conquer", Map.of(), 0);
        var service = new BattleService(17);
        int distance = service.calcInitialDistance(ARMY, ARMY,
                new BattleService.TechCtx(TECH, skills, 0), new BattleService.TechCtx(TECH, skills, 0));
        Map<String, Integer> mine = ARMY;
        Map<String, Integer> enemy = ARMY;
        Map<String, Integer> minePos = Map.of("infantry", 0);
        Map<String, Integer> enemyPos = Map.of("infantry", distance);
        int firstCombatRound = 0;
        StringBuilder log = new StringBuilder();
        for (int round = 1; round <= BattleService.MAX_ROUND; round++) {
            var state = service.resolveWorldRound(mine, enemy, minePos, enemyPos, distance,
                    TECH, TECH, skills, skills, 0, 0, 0, 0, 0, 0, round, firstCombatRound, Map.of(), Map.of());
            log.append(state.log());
            mine = state.attackerArmy();
            enemy = state.defenderArmy();
            minePos = state.attackerPositions();
            enemyPos = state.defenderPositions();
            firstCombatRound = state.firstCombatRound();
            if (state.finished()) break;
        }
        assertTrue(firstCombatRound > 1, "接敌前的机动回合不应提前触发技能");
        assertEquals(log.toString(), full.getReport().substring(full.getReport().indexOf("-- 第1回合"),
                full.getReport().indexOf("获得经验:")));
        assertEquals(mine, full.getSurvivorAttacker());
        assertEquals(enemy, full.getSurvivorDefender());
    }

    @Test
    void legacySessionRecoversFirstCombatIncludingShotsWithoutKills() {
        var service = new BattleService(0);
        assertEquals(0, service.firstCombatRoundFromLog(null));
        assertEquals(0, service.firstCombatRoundFromLog("-- 第3回合 --\n未开火\n"));
        assertEquals(4, service.firstCombatRoundFromLog("-- 第3回合 (军官加成生效) --\n未开火\n"
                + "-- 第4回合 (军官加成生效) --\n我方步兵 齐射 伤害0 击毁0\n"
                + "-- 第7回合 --\n敌方步兵 齐射 伤害100 击毁1\n"));
    }
}
