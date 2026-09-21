package com.wargame;

import com.wargame.service.BattleService;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.FortDef;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** 单次行动隔离后续回合，验证火力守恒、目标换算与攻守对称。 */
class BattleDamageTest {
    private final BattleService service = new BattleService(4096);

    @Test
    void exactFirepowerExhaustionStopsBeforeNextTarget() throws Exception {
        Map<String, Integer> targets = army("htank", 25, "bunker", 10);
        String report = act("rocket", 402, targets, 0, false, null, null);
        assertEquals(0, targets.get("htank"));
        assertEquals(10, targets.get("bunker"));
    }

    @Test
    void spilloverRecalculatesDefenseAndMatchupForEveryTarget() throws Exception {
        Map<String, Integer> targets = army("htank", 1, "bunker", 100);
        String report = act("rocket", 32, targets, 0, false, null, null);
        assertEquals(0, targets.get("htank"));
        assertTrue(targets.get("bunker") == 95);
        assertTrue(report.contains("余伤攻击敌碉堡(100) 对工事攻击179 伤害1296"), report);
        assertEquals(1, report.lines().filter(line -> line.contains("本次原始火力")).count());
    }

    @Test
    void antiAirBonusNeverSpillsIntoGroundTargets() throws Exception {
        Map<String, Integer> targets = army("scout", 1, "htank", 100);
        String report = act("armored", 10, targets, 100, false, null, null);
        assertEquals(0, targets.get("scout"));
        assertTrue(report.contains("余伤攻击敌" + UnitDef.UNITS.get("htank").name() + "(100) [前排承伤] 对地攻击18 伤害28"), report);
        assertTrue(targets.get("htank") >= 99);
    }

    @Test
    void weakAirAttackRemainsIndependentWithSkills() throws Exception {
        Map<String, Integer> targets = army("fighter", 100);
        String report = act("rocket", 100, targets, 0, false,
                context(Map.of("attack_tech", 10), Map.of("pierce", 5)), null);
        assertTrue(report.contains("本次原始火力750 伤害366"), report);
        assertTrue(targets.get("fighter") == 97);
    }

    @Test
    void excessGroundAttackSwitchesToWeakAirWeapon() throws Exception {
        Map<String, Integer> targets = army("infantry", 1, "fighter", 100);
        String report = act("rocket", 100, targets, 0, false, null, null);
        assertEquals(0, targets.get("infantry"));
        assertTrue(report.contains("余伤攻击敌" + UnitDef.UNITS.get("fighter").name() + "(100) 对空攻击5 伤害196"), report);
        assertTrue(targets.get("fighter") >= 98);
    }

    @Test
    void allEmplacementsUseFortAttackIncludingArtilleryClasses() throws Exception {
        // 对工事429独立于对地56；火炮仍可保留反炮兵分类，但不能误走对地武器。
        Map<String, Integer> damages = Map.of("bunker", 19500, "howitzer", 33000, "antitank", 30643, "flak", 30643);
        for (boolean defending : new boolean[]{false, true}) {
            for (var entry : damages.entrySet()) {
                String report = act("bomber", 100, army(entry.getKey(), 1000), 0, defending, null, null);
                assertTrue(report.contains("对工事攻击429 本次原始火力42900 伤害" + entry.getValue()), report);
            }
        }
    }

    @Test
    void logisticsUseWeakSiegeWeaponAtContact() throws Exception {
        Map<String, Integer> targets = army("infantry", 1, "bunker", 100);
        String report = act("truck", 1000, targets, 0, false, null, null);
        assertEquals(0, targets.get("infantry"));
        assertTrue(report.contains("对工事攻击1 伤害407"), report);
        assertTrue(targets.get("bunker") == 98 || targets.get("bunker") == 99);
    }

    @Test
    void submarineSwitchesFromSeaWeaponToWeakGroundFire() throws Exception {
        Map<String, Integer> targets = army("infantry", 1000, "battleship", 1);
        String report = act("sub", 150, targets, 0, false, null, null);
        assertEquals(0, targets.get("battleship"));
        assertTrue(report.contains("对海攻击66"), report);
        assertTrue(report.contains("对地攻击1 伤害"), report);
        assertTrue(targets.get("infantry") >= 997);
    }

    @Test
    void specialForcesUseImprovedSiegeFireAgainstEveryFort() throws Exception {
        for (String fort : new String[]{"bunker", "howitzer", "antitank", "flak"}) {
            String report = act("special", 100, army(fort, 10000), 0, false, null, null);
            assertTrue(report.contains("对工事攻击188 本次原始火力18800"), report);
            // 纯属性模式下无额外克制倍率，伤害完全由攻坚面板 188 与各工事防御减免决定
            assertTrue(report.contains("伤害" + switch (fort) {
                case "bunker" -> 8545;
                case "howitzer" -> 14462;
                default -> 13429;
            }), report);
        }
    }

    @Test
    void transportHasRealSelfDefenseAtContact() throws Exception {
        String report = act("transport", 100, army("fighter", 100), 0, false, null, null);
        assertTrue(report.contains("对空攻击1 本次原始火力100 伤害40"), report);
    }

    @Test
    void everyUnitAndFortCanDamageEveryDomainInRange() throws Exception {
        var attackers = new HashSet<>(UnitDef.UNITS.keySet());
        attackers.addAll(FortDef.FORTS.keySet());
        // 接触距离覆盖零射程后勤；每类目标分别行动，验证最低火力实际进入结算。
        for (String attacker : attackers) {
            for (String target : new String[]{"infantry", "fighter", "destroyer", "bunker"}) {
                Map<String, Integer> targets = army(target, 100000);
                String report = act(attacker, 10000, targets, 0, false, null, null);
                assertTrue(report.contains("伤害"), attacker + " -> " + target + report);
                assertTrue(targets.get(target) < 100000, attacker + " -> " + target);
            }
        }
    }

    @Test
    void weakAirWeaponDoesNotInheritGroundAttackOrAntiArmorBonus() throws Exception {
        Map<String, Integer> targets = army("scout", 100);
        String report = act("ltank", 100, targets, 100, false, null, null);
        assertTrue(report.contains("对空攻击10 本次原始火力1000 伤害606"), report);
        assertTrue(targets.get("scout") >= 90);
    }

    @Test
    void fractionalKillDoesNotCreateSpillover() throws Exception {
        Map<String, Integer> targets = army("htank", 1, "infantry", 10);
        String report = act("rocket", 1, targets, 350, false, null, null);
        assertEquals(10, targets.get("infantry"));
        assertFalse(report.contains("余伤攻击"), report);
    }

    @Test
    void movementStopsAtRangeAndDoesNotFireInSameAction() throws Exception {
        Map<String, Integer> targets = army("htank", 1);
        String report = act("rocket", 100, targets, 2001, false, null, null);
        assertEquals(1, targets.get("htank"));
        assertTrue(report.contains("前进 1 距离->2000"), report);
        assertFalse(report.contains("伤害"), report);
    }

    @Test
    void pointBlankDoesNotDoubleArtilleryDamage() throws Exception {
        Map<String, Integer> close = army("htank", 100);
        Map<String, Integer> far = army("htank", 100);
        String closeLog = act(new BattleService(4096), "rocket", 168, close, 0, false, null, null);
        String farLog = act(new BattleService(4096), "rocket", 168, far, 2000, false, null, null);
        assertEquals(close, far);
        assertTrue(closeLog.contains("伤害4024"));
        assertTrue(farLog.contains("伤害4024"));
    }

    @Test
    void defenderUsesSameSpilloverRulesAndSkipsDeadTargets() throws Exception {
        Map<String, Integer> targets = army("htank", 1, "bunker", 100, "scout", 0);
        String report = act("rocket", 32, targets, 0, true, null, null);
        assertEquals(0, targets.get("htank"));
        assertTrue(report.contains("余伤攻击我碉堡(100) 对工事攻击179 伤害1296"), report);
        assertFalse(report.contains("侦察机"), report);
    }

    @Test
    void targetsUseEffectiveHealthWithCommanderHp() throws Exception {
        Map<String, Integer> targets = army("htank", 1, "bunker", 100);
        String report = act("rocket", 32, targets, 0, false,
                context(Map.of(), Map.of()), context(Map.of("cmd_hp", 20), Map.of()));
        assertEquals(0, targets.get("htank"));
        assertTrue(report.contains("本次原始火力3200"), report);
    }

    private String act(String unit, int count, Map<String, Integer> targets, int distance,
                       boolean enemy, Object attackerContext, Object defenderContext) throws Exception {
        return act(service, unit, count, targets, distance, enemy, attackerContext, defenderContext);
    }

    private String act(BattleService svc, String unit, int count, Map<String, Integer> targets, int distance,
                       boolean enemy, Object attackerContext, Object defenderContext) throws Exception {
        Class<?> sideClass = Class.forName("com.wargame.service.BattleService$Side");
        Object side = Arrays.stream(sideClass.getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals(enemy ? "ENEMY" : "MINE"))
                .findFirst().orElseThrow();
        StringBuilder report = new StringBuilder();
        ReflectionTestUtils.invokeMethod(svc, "simAct", unit, army(unit, count), targets,
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
