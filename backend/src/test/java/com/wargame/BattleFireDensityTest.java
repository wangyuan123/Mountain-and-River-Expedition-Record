package com.wargame;

import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 隔离单次行动，用真实伤害事件验证独立倍率、余伤额度及双方科技来源。 */
class BattleFireDensityTest {
    private record Action(List<Map<String, Object>> events, String log, Map<String, Integer> targets) {}

    private BattleService.TechCtx context(Map<String, Integer> tech, Map<String, Integer> skills) {
        return new BattleService.TechCtx(tech, skills, 0, 0, CommandBonus.NONE);
    }

    private Action act(String unit, int count, Map<String, Integer> targets, boolean enemy,
                       BattleService.TechCtx own, BattleService.TechCtx foe, boolean active, String focus) {
        var engine = new BattleService(4096);
        List<Map<String, Object>> events = new ArrayList<>();
        ReflectionTestUtils.setField(engine, "practiceEvents", events);
        Class<?> sideClass = Arrays.stream(BattleService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Side")).findFirst().orElseThrow();
        Object side = Arrays.stream(sideClass.getEnumConstants())
                .filter(e -> ((Enum<?>) e).name().equals(enemy ? "ENEMY" : "MINE")).findFirst().orElseThrow();
        Map<String, Integer> army = new LinkedHashMap<>(Map.of(unit, count));
        Map<String, Integer> targetArmy = new LinkedHashMap<>(targets);
        Map<String, Integer> targetPositions = new LinkedHashMap<>();
        targetArmy.keySet().forEach(id -> targetPositions.put(id, 0));
        var report = new StringBuilder();
        ReflectionTestUtils.invokeMethod(engine, "fireUnitCombat", unit, army, targetArmy,
                enemy ? targetPositions : Map.of(unit, 0), enemy ? Map.of(unit, 0) : targetPositions,
                100, report, side, own, foe, active, focus);
        return new Action(events, report.toString(), targetArmy);
    }

    private Map<String, Object> first(Action action, String type) {
        return action.events.stream().filter(e -> type.equals(e.get("type"))).findFirst().orElseThrow();
    }

    private double number(Map<String, Object> event, String key) {
        return ((Number) event.get(key)).doubleValue();
    }

    @Test
    void everyUnitAndFortUsesLinearDensityInEveryDomainOnEitherSide() {
        Set<String> units = new TreeSet<>(UnitDef.UNITS.keySet());
        units.addAll(FortDef.FORTS.keySet());
        for (String unit : units) for (String target : List.of("infantry", "fighter", "destroyer", "bunker"))
            for (boolean enemy : new boolean[]{false, true}) {
                Action baseline = act(unit, 1000, Map.of(target, 1_000_000), enemy, null, null, false, target);
                double baseDamage = number(first(baseline, "FIRE"), "damage");
                for (int level : new int[]{0, 1, 5, 10}) {
                    Action developed = act(unit, 1000, Map.of(target, 1_000_000), enemy,
                            context(Map.of("fire_density", level), Map.of()), null, false, target);
                    var event = first(developed, "FIRE");
                    assertEquals(1 + level / 20.0, number(event, "firepowerMultiplier"), 1e-9);
                    assertEquals(baseDamage * (1 + level / 20.0), number(event, "damage"), 1e-8,
                            unit + " -> " + target + " side=" + enemy);
                    assertEquals(number(first(baseline, "FIRE"), "attack"), number(event, "attack"), 1e-9);
                    assertEquals(1, developed.events.stream().filter(e -> "FIRE".equals(e.get("type"))).count());
                }
            }
    }

    @Test
    void attackAndDensityMultiplyIndependentlyAndMissingLevelsPreserveOldDamage() {
        Action base = act("rocket", 1000, Map.of("bunker", 100000), false, null, null, false, "bunker");
        Action doubled = act("rocket", 1000, Map.of("bunker", 100000), false,
                context(Map.of("attack_tech", 10, "fire_density", 10), Map.of()), null, false, "bunker");
        assertEquals(number(first(base, "FIRE"), "damage") * 2.25, number(first(doubled, "FIRE"), "damage"), 1e-8);
        assertTrue(doubled.log.contains("火力密度×1.5"), doubled.log);
        assertTrue(doubled.log.contains("本次原始火力402750"), doubled.log);
        for (int invalid : new int[]{-10, 100}) {
            var event = first(act("rocket", 1000, Map.of("bunker", 100000), false,
                    context(Map.of("fire_density", invalid), Map.of()), null, false, "bunker"), "FIRE");
            assertEquals(invalid < 0 ? 1 : 1.5, number(event, "firepowerMultiplier"));
        }
    }

    @Test
    void matchingDensityAndHealthPreserveKillCountsAtFixedOtherStats() {
        for (int level : new int[]{1, 5, 10}) {
            Action baseline = act("rocket", 10000, Map.of("bunker", 100000), false,
                    context(Map.of(), Map.of()), context(Map.of(), Map.of()), false, "bunker");
            Action developed = act("rocket", 10000, Map.of("bunker", 100000), false,
                    context(Map.of("fire_density", level), Map.of()), context(Map.of("cmd_hp", level), Map.of()), false, "bunker");
            assertEquals(baseline.targets, developed.targets);
        }
    }

    @Test
    void spilloverConservesActionsAndCapsDamageBeforeChangingWeaponDomain() {
        for (boolean enemy : new boolean[]{false, true}) {
            Action action = act("rocket", 32, Map.of("htank", 1, "bunker", 1000), enemy,
                    context(Map.of("fire_density", 10), Map.of()), null, false, "htank");
            var fires = action.events.stream().filter(e -> "FIRE".equals(e.get("type"))).toList();
            assertEquals(2, fires.size());
            assertEquals(385, number(fires.get(0), "damage"), 1e-9);
            double consumed = 385 / (100 * 3 * 100.0 / (100 + 5 * 63.5) * 1.5);
            double expected = (32 - consumed) * 179 * 100.0 / (100 + 5 * 24) * 1.5;
            assertEquals(expected, number(fires.get(1), "damage"), 1e-8);
            assertEquals(1.5, number(fires.get(1), "firepowerMultiplier"));
            assertEquals(1, action.log.lines().filter(s -> s.contains("本次原始火力")).count());
        }
    }

    @Test
    void densityScalesLearnedAttackOnceWithoutCopyingEnemyDensity() {
        var skills = Map.of("frenzy", 5, "learn", 5, "pierce", 5);
        var foe = context(Map.of("attack_tech", 10), Map.of("suppress", 5));
        var targets = Map.of("infantry", 1000000);
        Action base = act("infantry", 1000, targets, false, context(Map.of("attack_tech", 5), skills), foe, true, "infantry");
        Action density = act("infantry", 1000, targets, false, context(Map.of("attack_tech", 5, "fire_density", 10), skills), foe, true, "infantry");
        Action foeDensity = act("infantry", 1000, targets, false, context(Map.of("attack_tech", 5, "fire_density", 10), skills),
                context(Map.of("attack_tech", 10, "fire_density", 10), Map.of("suppress", 5)), true, "infantry");
        assertEquals(4.5, number(first(density, "FIRE"), "learnedAttack"), 1e-9);
        assertEquals(number(first(base, "FIRE"), "damage") * 1.5, number(first(density, "FIRE"), "damage"), 1e-8);
        assertEquals(number(first(density, "FIRE"), "damage"), number(first(foeDensity, "FIRE"), "damage"), 1e-8);
    }

    @Test
    void counterUsesOwnDensityAndNeverPointBlankMultiplier() {
        for (boolean enemy : new boolean[]{false, true}) {
            Action action = act("infantry", 10000, Map.of("infantry", 10000), enemy,
                    context(Map.of("fire_density", 10), Map.of()),
                    context(Map.of("fire_density", 5), Map.of("counter", 5)), true, "infantry");
            var counter = first(action, "COUNTER");
            var hit = first(action, "FIRE");
            int remaining = 10000 - ((Number) hit.get("kills")).intValue();
            assertEquals(1.25, number(counter, "firepowerMultiplier"));
            assertEquals(remaining * 10 * 100.0 / 175 * 1.25, number(counter, "damage"), 1e-8);
            assertTrue(action.log.lines().filter(s -> s.contains("绝境反击")).allMatch(s -> !s.contains("贴脸×")));
        }
    }

    @Test
    void equipmentGroupsAndPracticeValuationIncludeDensityWithoutChangingRangeOrSpeed() {
        var engine = new BattleService(1);
        String group = "rocket~support";
        var gear = new HashMap<>(Map.of("attack_tech", 10, "@ue:" + group + ":" + "ground", 10));
        var baseline = engine.practiceStats(group, context(gear, Map.of()));
        gear.put("fire_density", 10);
        var stats = engine.practiceStats(group, context(gear, Map.of()));
        for (String field : List.of("ground", "air", "sea")) assertEquals(baseline.get(field) * 1.5, stats.get(field), 1e-8);
        for (String field : List.of("range", "speed", "hp", "defense")) assertEquals(baseline.get(field), stats.get(field));
        assertEquals(1.5, stats.get("firepowerMultiplier"));
        var a = Map.of("rocket", 100);
        var b = Map.of("bunker", 100);
        assertEquals(engine.calcInitialDistance(a, b, context(Map.of(), Map.of()), context(Map.of(), Map.of())),
                engine.calcInitialDistance(a, b, context(Map.of("fire_density", 10), Map.of()), context(Map.of("fire_density", 10), Map.of())));
    }
}
