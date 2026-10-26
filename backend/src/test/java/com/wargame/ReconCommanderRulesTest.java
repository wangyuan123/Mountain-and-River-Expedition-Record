package com.wargame;

import com.wargame.model.constants.UnitEquipmentEffects;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import com.wargame.service.UnitEquipmentRecon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconCommanderRulesTest {
    private final BattleService battle = new BattleService(42);
    private BattleService.TechCtx ctx(int military, int defense, Map<String, Integer> skills) {
        return new BattleService.TechCtx(Map.of(), skills, military, defense);
    }
    private UnitEquipmentRecon.Result fight(BattleService.TechCtx a, BattleService.TechCtx d, int radarLevel) {
        return UnitEquipmentRecon.resolve("recon_car", 10000, Map.of("recon_car", 10000), battle, a, d, 0, 0, radarLevel);
    }
    private int[] survivors(UnitEquipmentRecon.Result result, int round) {
        String line = result.log().get(round - 1).split("\n")[0];
        var matcher = java.util.regex.Pattern.compile("我方剩余(\\d+)，敌方剩余(\\d+)").matcher(line);
        assertTrue(matcher.find());
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private int[] expectedExchange(int[] prior, double aAttack, double dAttack,
                                   double aDurability, double dDurability, boolean attackerFirst) {
        int a = prior[0], d = prior[1];
        if (attackerFirst) {
            d -= Math.min(d, (int) Math.floor(a * aAttack / dDurability) + 1);
            a -= Math.min(a, (int) Math.floor(d * dAttack / aDurability) + 1);
        } else {
            a -= Math.min(a, (int) Math.floor(d * dAttack / aDurability) + 1);
            d -= Math.min(d, (int) Math.floor(a * aAttack / dDurability) + 1);
        }
        return new int[]{a, d};
    }

    @Test void noCommanderUsesOriginalDamageFormulaWithAlternatingEqualInitiative() {
        var car = UnitEquipmentRecon.resolve("recon_car", 210000, Map.of("recon_car", 210000),
                Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, 0, 0, 0);
        assertEquals(132956, car.ownAlive());
        assertEquals(132745, car.enemyAlive().get("recon_car"));
        assertArrayEquals(new int[]{200822, 200381}, survivors(car, 1));
        assertArrayEquals(new int[]{191644, 191603}, survivors(car, 2));
        var plane = UnitEquipmentRecon.resolve("scout", 100, Map.of("scout", 100),
                Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, 0, 0, 0);
        assertEquals(58, plane.ownAlive());
        assertEquals(58, plane.enemyAlive().get("scout"));
    }

    @Test void militaryAndDefenseApplyAtFirstCombatAndRepeatEveryThreeRounds() {
        var baseline = fight(ctx(0, 0, Map.of()), ctx(0, 0, Map.of()), 0);
        var military = fight(ctx(100, 0, Map.of()), ctx(0, 0, Map.of()), 0);
        var defense = fight(ctx(0, 100, Map.of()), ctx(0, 0, Map.of()), 0);
        for (var result : List.of(military, defense)) {
            for (int round : new int[]{1, 4, 7, 10}) assertTrue(result.log().get(round - 1).contains("属性 +100%"));
            for (int round : new int[]{2, 3, 5, 6, 8, 9}) assertFalse(result.log().get(round - 1).contains("属性 +100%"));
        }
        for (int round = 1; round <= 10; round++) {
            boolean active = (round - 1) % 3 == 0;
            int[] prior = round == 1 ? new int[]{10000, 10000} : survivors(military, round - 1);
            assertArrayEquals(expectedExchange(prior, active ? 36 : 18, 18, 393, 393, round % 2 == 1),
                    survivors(military, round), "军事属性只放大己方攻击，间隔回合关闭");
            prior = round == 1 ? new int[]{10000, 10000} : survivors(defense, round - 1);
            assertArrayEquals(expectedExchange(prior, 18, 18, active ? 426 : 393, 393, round % 2 == 1),
                    survivors(defense, round), "防御属性不能放大360生命值，间隔回合关闭");
        }
        var defenderMilitary = fight(ctx(0, 0, Map.of()), ctx(100, 0, Map.of()), 0);
        var defenderDefense = fight(ctx(0, 0, Map.of()), ctx(0, 100, Map.of()), 0);
        assertTrue(defenderMilitary.ownAlive() < baseline.ownAlive());
        assertTrue(defenderDefense.enemyAlive().get("recon_car") > baseline.enemyAlive().get("recon_car"));
    }

    @ParameterizedTest @ValueSource(strings = {"frenzy", "bulwark", "blitz", "suppress", "pierce", "counter", "learn", "borrow_armor"})
    void allEightSkillsAffectCombatAndCloseBetweenTheirWindows(String skill) {
        int radarLevel = 0;
        var boosted = fight(ctx(0, 0, Map.of(skill, 5)), ctx(0, 0, Map.of()), radarLevel);
        var plain = fight(ctx(0, 0, Map.of()), ctx(0, 0, Map.of()), radarLevel);
        // 同速首回合本就由攻方先手，闪电突击要在第4回合打破偶数回合轮换才体现战损差异。
        assertFalse(Arrays.equals(survivors(plain, "blitz".equals(skill) ? 4 : 1), survivors(boosted, "blitz".equals(skill) ? 4 : 1)), skill);
        for (int round : new int[]{2, 3, 5, 6, 8, 9}) {
            int[] prev = survivors(boosted, round - 1);
            assertArrayEquals(expectedExchange(prev, 18, 18, 393, 393, round % 2 == 1),
                    survivors(boosted, round), skill + "间隔回合不能泄漏技能");
        }
        for (int round : new int[]{1, 4, 7, 10}) assertFalse(boosted.log().get(round - 1).contains("本回合无将领属性或技能加成生效\n敌方"));
        if ("blitz".equals(skill)) {
            assertTrue(boosted.attackerInitiative() > boosted.defenderInitiative());
            assertTrue(boosted.log().get(0).contains("本回合先手：我方"));
            assertTrue(boosted.log().get(1).contains("本回合先手：敌方"));
        }
    }

    @Test void pierceIgnoresDefenseWithoutReducingHpAndFrenzyDoesNotMultiplyLearning() {
        var pierce = fight(ctx(0, 0, Map.of("pierce", 5)), ctx(0, 0, Map.of()), 0);
        assertEquals(10000 - (int) Math.floor(180000 / (360 + 33 * .7)) - 1, survivors(pierce, 1)[1]);
        var combined = fight(ctx(0, 0, Map.of("frenzy", 5, "learn", 5)), ctx(100, 0, Map.of("suppress", 5)), 0);
        // 学习独立追加敌方完整军事参考值；冲锋不放大、压制不削减学习所得攻击。
        double attack = 18 * 1.5 * .7 + 18 * 2 * .3;
        assertEquals(10000 - (int) Math.floor(10000 * attack / 393) - 1, survivors(combined, 1)[1]);
    }

    @Test void borrowedArmorExcludesHpOfficerAndChiefAndBulwarkWinsHistoricalConflict() {
        var defender = ctx(100, 200, Map.of());
        var borrowed = fight(ctx(0, 0, Map.of("borrow_armor", 5)), defender, 0);
        int enemy = survivors(borrowed, 1)[1];
        assertEquals(10000 - (int) Math.floor(enemy * 36.0 / (360 + 33 * 1.3)) - 1, survivors(borrowed, 1)[0]);
        var bulwark = fight(ctx(0, 0, Map.of("bulwark", 5)), defender, 0);
        var conflict = fight(ctx(0, 0, Map.of("bulwark", 5, "borrow_armor", 5)), defender, 0);
        assertEquals(bulwark.ownAlive(), conflict.ownAlive());
        assertEquals(bulwark.enemyAlive(), conflict.enemyAlive());
    }

    @Test void counterUsesSurvivingTroopsAndNoFreeKillOrRecursiveCounterOrLearning() {
        var counter = fight(ctx(0, 0, Map.of()), ctx(0, 0, Map.of("counter", 5)), 0);
        int d = 10000 - (int) Math.floor(180000.0 / 393) - 1;
        int aAfterCounter = 10000 - (int) Math.floor(d * 18.0 / 393);
        assertArrayEquals(new int[]{aAfterCounter - (int) Math.floor(d * 18.0 / 393) - 1, d}, survivors(counter, 1));
        var learned = fight(ctx(0, 0, Map.of()), ctx(0, 0, Map.of("counter", 5, "learn", 5)), 0);
        assertEquals(aAfterCounter - (int) Math.floor(d * 23.4 / 393) - 1, survivors(learned, 1)[0]);
        var both = fight(ctx(0, 0, Map.of("counter", 5)), ctx(0, 0, Map.of("counter", 5)), 0);
        assertEquals(2, both.log().get(0).split("绝境反击：", -1).length - 1);
        var eliminated = UnitEquipmentRecon.resolve("scout", 10000, Map.of("scout", 1), battle,
                ctx(0, 0, Map.of()), ctx(0, 0, Map.of("counter", 5)), 0, 0, 0);
        assertEquals(10000, eliminated.ownAlive());
        assertFalse(eliminated.log().get(0).contains("绝境反击："));
    }

    @Test void equipmentGroupsDoNotMultiplyFreeKillsAndOverflowUsesActualDurability() {
        Map<String, Integer> army = new LinkedHashMap<>(); army.put("recon_car", 1); army.put("recon_car~1", 9999);
        Map<String, Integer> gear = Map.of(UnitEquipmentEffects.key("recon_car~1", "hp"), 20);
        var result = UnitEquipmentRecon.resolve("recon_car", 10000, army, battle,
                ctx(0, 0, Map.of("frenzy", 5)), new BattleService.TechCtx(gear, Map.of(), 0, 0), 0, 0, 0);
        assertEquals(0, result.enemyAlive().get("recon_car"));
        assertEquals(10000 - 1 - (int) Math.floor(270000 / (33 + 360 * 1.2)), survivors(result, 1)[1]);
    }

    @Test void disadvantageUsesActualAttacksWithFirstCombatBonuses() {
        var weak = UnitEquipmentRecon.resolve("recon_car", 1000, Map.of("recon_car", 10000), battle,
                ctx(100, 100, Map.of("frenzy", 5)), ctx(0, 0, Map.of()), 0, 0, 0);
        assertEquals(480000, weak.ownPower());
        assertArrayEquals(new int[]{583, 9862}, survivors(weak, 1));
        assertFalse(weak.log().isEmpty(), "战力不足30%也必须按实际攻击逐回合交火");

        var plain = UnitEquipmentRecon.resolve("recon_car", 1000, Map.of("recon_car", 3400), battle,
                ctx(0, 0, Map.of()), ctx(0, 0, Map.of()), 0, 0, 0);
        var boosted = UnitEquipmentRecon.resolve("recon_car", 1000, Map.of("recon_car", 3400), battle,
                ctx(100, 100, Map.of()), ctx(0, 0, Map.of()), 0, 0, 0);
        assertArrayEquals(new int[]{846, 3354}, survivors(plain, 1));
        assertArrayEquals(new int[]{860, 3308}, survivors(boosted, 1));
        assertTrue(boosted.enemyAlive().get("recon_car") < plain.enemyAlive().get("recon_car"));
    }

    @Test void unrelatedTechAndEconomicSkillsDoNotAffectReconCombat() {
        var empty = fight(ctx(0, 0, Map.of()), ctx(0, 0, Map.of()), 0);
        var unrelated = new BattleService.TechCtx(Map.of("attack_tech", 10, "defense_tech", 10, "hp_tech", 10, "fire_density", 10),
                Map.of("medic", 5, "leadership", 5, "finance", 5), 0, 0);
        var ignored = fight(unrelated, unrelated, 0);
        assertEquals(empty.ownAlive(), ignored.ownAlive()); assertEquals(empty.enemyAlive(), ignored.enemyAlive());
        assertTrue(UnitEquipmentRecon.resolve("scout", 1, Map.of("recon_car", 10000), battle,
                ctx(100, 100, Map.of("frenzy", 5)), ctx(100, 100, Map.of("counter", 5)), 0, 0, 0).log().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void bothReconModesUseOriginalDamageFormulaWithPeriodicAttributesAndSkills(String unit) {
        var result = UnitEquipmentRecon.resolve(unit, 10000, Map.of(unit, 10000), battle,
                ctx(70, 40, Map.of("frenzy", 5)), ctx(55, 77, Map.of("bulwark", 3)), 0, 0, 0);
        var stats = com.wargame.model.constants.UnitDef.UNITS.get(unit);
        double base = "scout".equals(unit) ? stats.atkAir() : stats.atkGround();
        assertEquals(10, result.log().size());
        int own = 10000, enemy = 10000;
        for (int round = 1; round <= 10; round++) {
            boolean active = (round - 1) % 3 == 0;
            double aAttack = base * (active ? 1.7 * 1.5 : 1);
            double dDurability = stats.hp() + stats.def() * (active ? 1.77 * 1.3 : 1);
            double dAttack = base * (active ? 1.55 : 1);
            double aDurability = stats.hp() + stats.def() * (active ? 1.4 : 1);
            int[] expected = expectedExchange(new int[]{own, enemy}, aAttack, dAttack, aDurability, dDurability, round % 2 == 1);
            own = expected[0]; enemy = expected[1];
            assertArrayEquals(new int[]{own, enemy}, survivors(result, round), unit + "第" + round + "回合");
            assertEquals(active, result.log().get(round - 1).contains("军事属性 +70%攻击"));
            assertEquals(active, result.log().get(round - 1).contains("全军冲锋"));
        }
    }
}
