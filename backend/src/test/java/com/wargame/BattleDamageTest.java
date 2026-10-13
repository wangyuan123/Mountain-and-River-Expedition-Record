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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 单次行动隔离后续回合，验证火力守恒、目标换算与攻守对称。 */
class BattleDamageTest {
    private final BattleService service = new BattleService(4096);

    @Test
    void exactFirepowerExhaustionStopsBeforeNextTarget() throws Exception {
        // ×3 下 12859 辆反坦克火箭车的攻击额度恰好击毁 2400 辆重坦，验证没有余伤再命中碉堡。
        Map<String, Integer> targets = army("htank", 2_400, "bunker", 10);
        String report = act("rocket", 12_859, targets, 0, false, null, null, "htank");
        assertEquals(0, targets.get("htank"));
        assertEquals(10, targets.get("bunker"));
        assertFalse(report.contains("余伤攻击"), report);
    }

    @Test
    void spilloverRecalculatesDefenseAndMatchupForEveryTarget() throws Exception {
        Map<String, Integer> targets = army("htank", 1, "bunker", 100);
        String report = act("rocket", 32, targets, 0, false, null, null, "htank");
        assertEquals(0, targets.get("htank"));
        assertTrue(targets.get("bunker") >= 91 && targets.get("bunker") <= 92);
        assertTrue(report.contains("倍率×3.0 相克"), report);
        assertTrue(report.contains("余伤攻击敌碉堡(100) 对工事攻击179 伤害2168"), report);
        assertEquals(1, report.lines().filter(line -> line.contains("本次原始火力")).count());
        String rocketName = UnitDef.UNITS.get("rocket").name();
        assertEquals(1, report.lines().filter(line -> line.startsWith("我方" + rocketName)).count(), report);
    }

    @Test
    void antiAirBonusNeverSpillsIntoGroundTargets() throws Exception {
        Map<String, Integer> targets = army("fighter", 1, "htank", 100);
        String report = act("armored", 10, targets, 100, false, null, null, "fighter");
        assertEquals(0, targets.get("fighter"));
        assertTrue(report.contains("倍率×2.5 相克"), report);
        assertTrue(report.contains("余伤攻击敌" + UnitDef.UNITS.get("htank").name() + "(100) [前排承伤] 对地攻击18 伤害24"), report);
        assertTrue(targets.get("htank") >= 99);
    }

    @Test
    void weakAirAttackRemainsIndependentWhenPiercingIsOnCooldown() throws Exception {
        Map<String, Integer> targets = army("fighter", 100);
        String report = act("rocket", 100, targets, 0, false,
                context(Map.of("attack_tech", 10), Map.of("pierce", 5)), null);
        assertTrue(report.contains("本次原始火力2300 伤害920"), report);
        assertFalse(report.contains("破甲"), report);
        assertEquals(93, targets.get("fighter"));
    }

    @Test
    void excessGroundAttackSwitchesToWeakAirWeapon() throws Exception {
        Map<String, Integer> targets = army("infantry", 1, "fighter", 100);
        String report = act("rocket", 100, targets, 0, false, null, null, "infantry");
        assertEquals(0, targets.get("infantry"));
        assertTrue(report.contains("余伤攻击敌" + UnitDef.UNITS.get("fighter").name() + "(100) 对空攻击12 伤害450"), report);
        assertEquals(97, targets.get("fighter"));
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
        String report = act("truck", 1000, targets, 0, false, null, null, "infantry");
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
            // 特种兵贴脸时获得 1.5 倍最终伤害；其余伤害仍由攻坚面板188与工事防御减免决定。
            assertTrue(report.contains("伤害" + switch (fort) {
                case "bunker" -> 12818;
                case "howitzer" -> 21692;
                default -> 20143;
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
        String report = act("rocket", 1, targets, 350, false, null, null, "htank");
        assertEquals(10, targets.get("infantry"));
        assertFalse(report.contains("余伤攻击"), report);
    }

    @Test
    void movementContinuesPastRangeWithoutFiringInSameAction() throws Exception {
        Map<String, Integer> targets = army("htank", 1);
        String report = act("rocket", 100, targets, 1601, false, null, null);
        assertEquals(1, targets.get("htank"));
        assertTrue(report.contains("前进 150 距离->1451"), report);
        assertFalse(report.contains("伤害"), report);
    }

    @Test
    void pointBlankDoesNotDoubleArtilleryDamage() throws Exception {
        Map<String, Integer> close = army("htank", 100);
        Map<String, Integer> far = army("htank", 100);
        String closeLog = act(new BattleService(4096), "rocket", 168, close, 0, false, null, null);
        String farLog = act(new BattleService(4096), "rocket", 168, far, 1600, false, null, null);
        assertEquals(close, far);
        assertTrue(closeLog.contains("伤害12072"), closeLog);
        assertTrue(farLog.contains("伤害12072"), farLog);
    }

    @Test
    void rocketRangeBoundaryAndTechnologyAreReflectedInCombatLogs() throws Exception {
        String boundary = act("rocket", 1, army("htank", 100), 1600, false, null, null, "htank");
        String outside = act("rocket", 1, army("htank", 100), 1601, false, null, null, "htank");
        String developed = act("rocket", 1, army("htank", 100), 1920, false,
                context(Map.of("weapon_range", 2), Map.of()), null, "htank");
        String developedOutside = act("rocket", 1, army("htank", 100), 1921, false,
                context(Map.of("weapon_range", 2), Map.of()), null, "htank");

        assertTrue(boundary.contains("伤害"), boundary);
        assertTrue(boundary.contains("有效射程1600 目标距离1600"), boundary);
        assertFalse(outside.contains("伤害"), outside);
        assertTrue(developed.contains("有效射程1920 目标距离1920"), developed);
        assertFalse(developedOutside.contains("伤害"), developedOutside);
    }

    @Test
    void infantryDoublesPointBlankDamageWhileOtherMeleeUnitsKeepTheirBonusOnEitherSide() throws Exception {
        for (boolean defending : new boolean[]{false, true}) {
            for (String unit : new String[]{"infantry", "ltank", "htank", "special"}) {
                String close = act(unit, 100, army("infantry", 100_000), 0, defending, null, null);
                String far = act(unit, 100, army("infantry", 100_000), 1, defending, null, null);
                double multiplier = "infantry".equals(unit) ? 2.0 : 1.5;
                assertTrue(close.contains("贴脸×" + multiplier), unit + close);
                assertFalse(far.contains("贴脸×"), unit + far);
                assertEquals(multiplier, (double) reportedDamage(close) / reportedDamage(far), 0.01, unit);
            }
            for (String unit : new String[]{"armored", "assault", "rocket", "fighter", "bomber", "howitzer"}) {
                String close = act(unit, 100, army("infantry", 100_000), 0, defending, null, null);
                String far = act(unit, 100, army("infantry", 100_000), 1, defending, null, null);
                assertFalse(close.contains("贴脸×"), unit + close);
                assertEquals(reportedDamage(far), reportedDamage(close), unit);
            }
        }
    }

    @Test
    void infantrySpilloverSharesItsBudgetAndRechecksContactDistance() throws Exception {
        for (int fortDistance : new int[]{0, 1}) {
            Map<String, Integer> targets = army("infantry", 1, "bunker", 100_000);
            Class<?> sideClass = Class.forName("com.wargame.service.BattleService$Side");
            Object side = Arrays.stream(sideClass.getEnumConstants())
                    .filter(value -> ((Enum<?>) value).name().equals("MINE"))
                    .findFirst().orElseThrow();
            StringBuilder report = new StringBuilder();
            ReflectionTestUtils.invokeMethod(service, "fireUnitCombat", "infantry", army("infantry", 100), targets,
                    Map.of("infantry", 0), Map.of("infantry", 0, "bunker", fortDistance),
                    160, report, side, null, null, 0, false, "infantry");

            // 消灭一个贴脸步兵消耗 10.5 份攻击额度；剩余 89.5 份按碉堡距离计算攻坚伤害。
            assertEquals(0, targets.get("infantry"));
            assertTrue(report.toString().contains("贴脸×2.0"), report.toString());
            String spillover = report.substring(report.indexOf("余伤攻击"));
            assertEquals(fortDistance == 0, spillover.contains("贴脸×2.0"), spillover);
            assertEquals(fortDistance == 0 ? 244 : 122, reportedDamage(spillover), spillover);
        }
    }

    @Test
    void infantryCounterattackAtContactDoesNotReceiveDoubleDamage() {
        Map<String, BattleService.UnitOrder> hold = Map.of("infantry",
                new BattleService.UnitOrder(BattleService.CommandAction.HOLD));
        var state = new BattleService(4096).resolveWorldRound(
                army("infantry", 100), army("infantry", 100),
                Map.of("infantry", 200), Map.of("infantry", 200), 400,
                Map.of(), Map.of(), Map.of(), Map.of("counter", 5),
                0, 0, 0, 0, 0, 0, 4, 4, hold, hold);

        assertTrue(state.log().contains("贴脸×2.0"), state.log());
        var counter = Pattern.compile("敌方[^\\n]*\\((\\d+)\\) \\[绝境反击\\][^\\n]*? 伤害(\\d+)")
                .matcher(state.log());
        assertTrue(counter.find(), state.log());
        assertFalse(counter.group().contains("贴脸×"), state.log());
        var infantry = UnitDef.UNITS.get("infantry");
        long normalDamage = Math.round(Integer.parseInt(counter.group(1)) * infantry.atkGround() * 100.0
                / (100 + 5 * infantry.def()));
        assertEquals(normalDamage, Long.parseLong(counter.group(2)), state.log());
    }

    @Test
    void defenderUsesSameSpilloverRulesAndSkipsDeadTargets() throws Exception {
        Map<String, Integer> targets = army("htank", 1, "bunker", 100, "scout", 0);
        String report = act("rocket", 32, targets, 0, true, null, null, "htank");
        assertEquals(0, targets.get("htank"));
        assertTrue(report.contains("余伤攻击我碉堡(100) 对工事攻击179 伤害2168"), report);
        assertFalse(report.contains("侦察机"), report);
    }

    @Test
    void targetsUseEffectiveHealthWithCommanderHp() throws Exception {
        Map<String, Integer> targets = army("htank", 1, "bunker", 100);
        String report = act("rocket", 32, targets, 0, false,
                context(Map.of(), Map.of()), context(Map.of("cmd_hp", 20), Map.of()), "htank");
        assertEquals(0, targets.get("htank"));
        assertTrue(report.contains("本次原始火力3200"), report);
    }

    private String act(String unit, int count, Map<String, Integer> targets, int distance,
                       boolean enemy, Object attackerContext, Object defenderContext) throws Exception {
        return act(service, unit, count, targets, distance, enemy, attackerContext, defenderContext);
    }

    private String act(String unit, int count, Map<String, Integer> targets, int distance,
                       boolean enemy, Object attackerContext, Object defenderContext, String focusTarget) throws Exception {
        return act(service, unit, count, targets, distance, enemy, attackerContext, defenderContext, focusTarget);
    }

    private String act(BattleService svc, String unit, int count, Map<String, Integer> targets, int distance,
                       boolean enemy, Object attackerContext, Object defenderContext) throws Exception {
        return act(svc, unit, count, targets, distance, enemy, attackerContext, defenderContext, null);
    }

    private String act(BattleService svc, String unit, int count, Map<String, Integer> targets, int distance,
                       boolean enemy, Object attackerContext, Object defenderContext, String focusTarget) throws Exception {
        Class<?> sideClass = Class.forName("com.wargame.service.BattleService$Side");
        Object side = Arrays.stream(sideClass.getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals(enemy ? "ENEMY" : "MINE"))
                .findFirst().orElseThrow();
        StringBuilder report = new StringBuilder();
        if (focusTarget == null) {
            ReflectionTestUtils.invokeMethod(svc, "simAct", unit, army(unit, count), targets,
                    distance, report, side, attackerContext, defenderContext, 0, false);
        } else {
            // 余伤用例显式指定首个目标，避免同距离 ID 排序改变需要验证的领域切换顺序。
            Map<String, Integer> myPositions = Map.of(unit, enemy ? distance : 0);
            Map<String, Integer> foePositions = new LinkedHashMap<>();
            targets.keySet().forEach(target -> foePositions.put(target, enemy ? 0 : distance));
            ReflectionTestUtils.invokeMethod(svc, "fireUnitCombat", unit, army(unit, count), targets,
                    enemy ? foePositions : myPositions, enemy ? myPositions : foePositions,
                    distance, report, side, attackerContext, defenderContext, 0, false, focusTarget);
        }
        return report.toString();
    }

    private Object context(Map<String, Integer> tech, Map<String, Integer> skills) throws Exception {
        Class<?> contextClass = Class.forName("com.wargame.service.BattleService$TechCtx");
        Constructor<?> constructor = contextClass.getDeclaredConstructor(Map.class, Map.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(tech, skills, 0);
    }

    private long reportedDamage(String report) {
        var matcher = Pattern.compile(" 伤害(\\d+)").matcher(report);
        assertTrue(matcher.find(), report);
        return Long.parseLong(matcher.group(1));
    }

    private Map<String, Integer> army(Object... pairs) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (Integer) pairs[i + 1]);
        return result;
    }
}
