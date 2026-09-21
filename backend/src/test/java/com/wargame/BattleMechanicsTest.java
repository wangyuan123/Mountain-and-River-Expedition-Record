package com.wargame;

import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖伤害增长、技能归属、城墙归属和混编保护，避免调数值掩盖规则错误。 */
class BattleMechanicsTest {
    private Object side(boolean defender) throws Exception {
        return Arrays.stream(Class.forName("com.wargame.service.BattleService$Side").getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals(defender ? "ENEMY" : "MINE"))
                .findFirst().orElseThrow();
    }

    private Object context(Map<String, Integer> tech, Map<String, Integer> skills) throws Exception {
        Constructor<?> constructor = Class.forName("com.wargame.service.BattleService$TechCtx")
                .getDeclaredConstructor(Map.class, Map.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(tech, skills, 0);
    }

    private long damage(boolean defending, Object own, Object foe, int foeWall, boolean active) throws Exception {
        StringBuilder report = new StringBuilder();
        ReflectionTestUtils.invokeMethod(new BattleService(4096), "simAct", "infantry",
                new LinkedHashMap<>(Map.of("infantry", 100)), new LinkedHashMap<>(Map.of("infantry", 10000)),
                100, report, side(defending), own, foe, foeWall, active);
        var matcher = Pattern.compile(" 伤害(\\d+)").matcher(report);
        assertTrue(matcher.find(), report.toString());
        return Long.parseLong(matcher.group(1));
    }

    @Test
    void attackTechnologyAddsLinearDamageOutsideOfficerRounds() throws Exception {
        long base = damage(false, context(Map.of(), Map.of()), null, 0, false);
        long boosted = damage(false, context(Map.of("attack_tech", 2), Map.of()), null, 0, false);
        assertEquals(343, base);
        assertEquals(377, boosted, "攻击增加10%应只增加10%伤害，并在普通回合生效");
    }

    @Test
    void suppressionReducesEnemyAttackRatherThanItsOwner() throws Exception {
        Object empty = context(Map.of(), Map.of());
        Object suppression = context(Map.of(), Map.of("suppress", 1));
        for (boolean defending : new boolean[]{false, true}) {
            assertEquals(343, damage(defending, suppression, empty, 0, true));
            assertEquals(326, damage(defending, empty, suppression, 0, true), "压制光环在军官回合降低敌方5%伤害");
            assertEquals(326, damage(defending, empty, suppression, 0, false), "压制光环在普通回合常驻生效降低敌方5%伤害");
        }
    }

    @Test
    void defenseAndPiercingWorkForBothSidesAndWallOnlyProtectsCity() throws Exception {
        Object empty = context(Map.of(), Map.of());
        Object defense = context(Map.of("defense_tech", 10), Map.of());
        Object pierce = context(Map.of(), Map.of("pierce", 5));
        for (boolean defending : new boolean[]{false, true}) {
            assertEquals(282, damage(defending, empty, defense, 0, false));
            assertEquals(336, damage(defending, pierce, defense, 0, false));
        }
        assertEquals(223, damage(false, empty, defense, 10, false), "攻击守城军时城墙生效");
        assertEquals(282, damage(true, empty, defense, 10, false), "出征军不能携带城墙防御");
    }

    @Test
    void bulwarkWorksInOfficerRounds() throws Exception {
        Object empty = context(Map.of(), Map.of());
        Object bulwark = context(Map.of(), Map.of("bulwark", 5));
        for (boolean defending : new boolean[]{false, true}) {
            assertEquals(282, damage(defending, empty, bulwark, 0, true));
            assertEquals(343, damage(defending, empty, bulwark, 0, false));
        }
    }

    @Test
    void heavyTankProtectsGroundRearButDoesNotHideAircraftOrStopInfiltration() throws Exception {
        var service = new BattleService(19);
        Map<String, Integer> army = Map.of("htank", 10, "rocket", 20, "fighter", 15);
        Map<String, Integer> mine = Map.of("ltank", 0, "special", 0, "fighter", 0);
        Map<String, Integer> enemy = Map.of("htank", 100, "rocket", 100, "fighter", 120);
        assertEquals("htank", ReflectionTestUtils.invokeMethod(service, "pickTargetInRange",
                "ltank", side(false), army, mine, enemy, 2200, null));
        assertEquals("rocket", ReflectionTestUtils.invokeMethod(service, "pickTargetInRange",
                "special", side(false), army, mine, enemy, 2200, null));
        assertEquals("fighter", ReflectionTestUtils.invokeMethod(service, "pickTargetInRange",
                "fighter", side(false), army, mine, enemy, 2200, null));
        assertEquals("rocket", ReflectionTestUtils.invokeMethod(service, "pickTargetInRange",
                "ltank", side(false), army, mine, Map.of("htank", 100, "rocket", 50, "fighter", 120), 2200, null));
    }

    @Test
    void weaponRangeTechnologyChangesActualReach() throws Exception {
        var service = new BattleService(19);
        Map<String, Integer> foe = Map.of("infantry", 10);
        Map<String, Integer> mine = Map.of("infantry", 0);
        Map<String, Integer> enemy = Map.of("infantry", 110);
        assertNull(ReflectionTestUtils.invokeMethod(service, "pickTargetInRange",
                "infantry", side(false), foe, mine, enemy, 2200, null));
        assertEquals("infantry", ReflectionTestUtils.invokeMethod(service, "pickTargetInRange",
                "infantry", side(false), foe, mine, enemy, 2200, context(Map.of("weapon_range", 2), Map.of())));
    }

    @Test
    void infantryCanUseWeakFireAgainstNearbyAircraft() throws Exception {
        var mine = new LinkedHashMap<>(Map.of("infantry", 0));
        var enemy = new LinkedHashMap<>(Map.of("fighter", 50, "motor", 500));
        StringBuilder report = new StringBuilder();
        ReflectionTestUtils.invokeMethod(new BattleService(19), "simAct", "infantry",
                new LinkedHashMap<>(Map.of("infantry", 10)), new LinkedHashMap<>(Map.of("fighter", 10, "motor", 10)),
                mine, enemy, 2200, report, side(false), null, null, 0, false);
        assertEquals(0, mine.get("infantry"), "飞机在射程内时应使用弱对空火力");
        assertTrue(report.toString().contains("对空攻击5 本次原始火力50 伤害20"));
    }

    @Test
    void passiveLogisticsStillWaitOutsideTheirRange() {
        var result = new BattleService(19).resolveWild(Map.of("transport", 10), Map.of("truck", 10));
        assertFalse(result.isWin(), "双方后勤不主动接敌，30回合后攻方撤退");
        assertEquals(Map.of("truck", 10), result.getSurvivorAttacker());
        assertEquals(Map.of("transport", 10), result.getSurvivorDefender());
        assertFalse(result.getReport().contains("伤害"));
    }
}
