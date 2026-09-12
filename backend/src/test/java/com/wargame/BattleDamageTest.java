package com.wargame;

import com.wargame.model.dto.BattleResult;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BattleDamageTest {
    private final BattleService service = new BattleService();

    @Test
    void rocketSalvoCarriesDamageFromTanksThroughMultipleFortifications() throws Exception {
        Map<String, Integer> defenders = army("htank", 33, "howitzer", 61, "bunker", 88);
        String report = act("rocket", 1054, defenders, 0, false, null, null);

        // 截图中的齐射：64030.5，总生命值为 7260 + 4880 + 22880。
        assertEquals(army("htank", 0, "howitzer", 0, "bunker", 0), defenders);
        assertTrue(report.contains("本次总伤害64031 伤害7260 击毁33 剩余伤害56771"), report);
        assertTrue(report.contains("余伤攻击敌榴弹炮(61) 伤害4880 击毁61 剩余伤害51891"), report);
        assertTrue(report.contains("余伤攻击敌碉堡(88) 伤害22880 击毁88 剩余伤害29011"), report);
        assertEquals(1, report.lines().filter(line -> line.contains("齐射")).count());
    }

    @Test
    void switchingTargetsConsumesOnlyTheRemainingDamage() throws Exception {
        Map<String, Integer> defenders = army("htank", 33, "howitzer", 500, "bunker", 88);
        String report = act("rocket", 400, defenders, 0, false, null, null);

        // 总伤害 24300，重坦消耗 7260，余下 17040 只能击毁 213 门榴弹炮。
        assertEquals(0, defenders.get("htank"));
        assertEquals(287, defenders.get("howitzer"));
        assertEquals(88, defenders.get("bunker"));
        assertTrue(report.contains("余伤攻击敌榴弹炮(500) 伤害17040 击毁213 剩余伤害0"), report);
        assertFalse(report.contains("余伤攻击敌碉堡"), report);
    }

    @Test
    void exactDamageExhaustionStopsBeforeTheNextTarget() throws Exception {
        Map<String, Integer> defenders = army("infantry", 6, "truck", 5);
        String report = act("infantry", 100, defenders, 0, false, null, null);

        assertEquals(0, defenders.get("infantry")); // 180 伤害，正好击毁 6 个 30 HP 步兵。
        assertEquals(5, defenders.get("truck"));
        assertFalse(report.contains("余伤攻击"), report);
    }

    @Test
    void fractionalKillNeverCreatesDamageForAnotherTarget() throws Exception {
        for (int i = 0; i < 30; i++) {
            Map<String, Integer> defenders = army("htank", 1, "infantry", 10);
            String report = act("rocket", 1, defenders, 350, false, null, null);
            // 30.375 不够击毁 220 HP 重坦；即使概率击杀成功也没有可转移余伤。
            assertTrue(defenders.get("htank") == 0 || defenders.get("htank") == 1);
            assertEquals(10, defenders.get("infantry"));
            assertFalse(report.contains("余伤攻击"), report);
        }
    }

    @Test
    void outOfRangeActionOnlyMovesEvenWhenTheMoveEntersRange() throws Exception {
        Map<String, Integer> defenders = army("htank", 33, "howitzer", 61);
        String report = act("rocket", 1054, defenders, 351, false, null, null);

        assertEquals(army("htank", 33, "howitzer", 61), defenders);
        assertTrue(report.contains("前进 200 距离->151"), report);
        assertFalse(report.contains("伤害"), report);
    }

    @Test
    void defenderAttacksAlsoCarryDamageAndSkipEmptyTargets() throws Exception {
        Map<String, Integer> attackers = army("htank", 33, "howitzer", 0, "bunker", 88);
        String report = act("rocket", 1054, attackers, 0, true, null, null);

        assertEquals(army("htank", 0, "howitzer", 0, "bunker", 0), attackers);
        assertTrue(report.contains("敌方火箭(1054)余伤攻击我碉堡(88)"), report);
        assertFalse(report.contains("榴弹炮"), report);
    }

    @Test
    void eachNewTargetUsesItsEffectiveHealthWithoutApplyingComboAgain() throws Exception {
        Map<String, Integer> defenders = army("htank", 33, "howitzer", 500, "bunker", 88);
        Object attackerContext = context(Map.of(), Map.of("combo", 13)); // 100% 以上，必定连击。
        Object defenderContext = context(Map.of("cmd_hp", 20), Map.of()); // 生命值翻倍。
        String report = act("rocket", 400, defenders, 0, false, attackerContext, defenderContext);

        // 连击总伤害 48600。重坦消耗 14520，剩余 34080 / 榴弹炮160 HP = 213。
        assertEquals(0, defenders.get("htank"));
        assertEquals(287, defenders.get("howitzer"));
        assertEquals(88, defenders.get("bunker"));
        assertTrue(report.contains("本次总伤害48600"), report);
        assertTrue(report.contains("伤害34080 击毁213 剩余伤害0"), report);
        assertEquals(1, report.lines().filter(line -> line.contains("连击")).count());
    }

    @Test
    void wildAndCityBattlesResolveSpilloverBeforeDestroyedEnemiesCanAct() {
        Map<String, Integer> attacker = Map.of("rocket", 1054);
        Map<String, Integer> defenders = army("htank", 33, "howitzer", 61, "bunker", 1);
        BattleResult wild = service.resolveWild(defenders, attacker);
        BattleResult city = service.startWorldDispatch(attacker, Map.of("htank", 33),
                Map.of("howitzer", 61, "bunker", 1), Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, "conquer", Map.of("food", 100), 0);

        for (BattleResult result : new BattleResult[]{wild, city}) {
            assertTrue(result.isWin(), result.getReport());
            assertTrue(result.getSurvivorDefender().isEmpty());
            assertTrue(result.getSurvivorAttacker().get("rocket") > 0);
            assertTrue(result.getReport().contains("余伤攻击敌碉堡"), result.getReport());
        }
        assertTrue(city.isCityConquered());
        assertEquals(army("htank", 33, "howitzer", 61, "bunker", 1), defenders,
                "计算应修改战斗副本，不能改变调用者传入的军队");
    }

    private String act(String unit, int count, Map<String, Integer> targets, int distance,
                       boolean enemy, Object attackerContext, Object defenderContext) throws Exception {
        Class<?> sideClass = Class.forName("com.wargame.service.BattleService$Side");
        Object side = Arrays.stream(sideClass.getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals(enemy ? "ENEMY" : "MINE"))
                .findFirst().orElseThrow();
        StringBuilder report = new StringBuilder();
        // 单次行动测试隔离后续回合，直接验证余伤守恒及射程边界。
        ReflectionTestUtils.invokeMethod(service, "simAct", unit, army(unit, count), targets,
                distance, report, side, attackerContext, defenderContext, 0, false);
        return report.toString();
    }

    private Object context(Map<String, Integer> tech, Map<String, Integer> skills) throws Exception {
        Class<?> contextClass = Class.forName("com.wargame.service.BattleService$TechCtx");
        Constructor<?> constructor = contextClass.getDeclaredConstructor(Map.class, Map.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(tech, skills, 0);
    }

    private Map<String, Integer> army(Object... pairs) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (Integer) pairs[i + 1]);
        return result;
    }
}
