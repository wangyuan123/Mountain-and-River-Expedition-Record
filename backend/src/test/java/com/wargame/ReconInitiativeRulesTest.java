package com.wargame;

import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.UnitEquipmentEffects;
import com.wargame.service.BattleService;
import com.wargame.service.UnitEquipmentRecon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证侦查先手的双方科技、首回合雷达与平值轮换实际参与出手和战损。 */
class ReconInitiativeRulesTest {
    private final BattleService battle = new BattleService(42);
    private final BattleService.TechCtx plain = new BattleService.TechCtx(Map.of(), Map.of(), 0, 0);

    private UnitEquipmentRecon.Result resolve(String unit, int attackerLevel, int defenderLevel, int radar) {
        return UnitEquipmentRecon.resolve(unit, 10000, Map.of(unit, 10000), battle, plain, plain,
                attackerLevel, defenderLevel, radar);
    }

    private void assertFirst(UnitEquipmentRecon.Result result, int round, boolean attacker, int aValue, int dValue) {
        assertTrue(result.log().get(round - 1).contains("本回合先手：" + (attacker ? "我方" : "敌方")
                + "（我方" + aValue + " / 敌方" + dValue + "）"), result.log().get(round - 1));
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void equalValuesAlternateIncludingWhenNeitherSideHasCommander(String unit) {
        var result = resolve(unit, 3, 3, 0);
        int initiative = UnitDef.UNITS.get(unit).spd() + 15;
        assertEquals(initiative, result.attackerInitiative());
        assertEquals(initiative, result.defenderInitiative());
        assertEquals(10, result.log().size());
        for (int round = 1; round <= 10; round++) assertFirst(result, round, round % 2 == 1, initiative, initiative);
        assertTrue(result.log().stream().noneMatch(line -> line.contains("将领加成")));
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void researchAdvantageWorksSymmetricallyAndChangesLosses(String unit) {
        var attacker = resolve(unit, 5, 2, 0);
        var defender = resolve(unit, 2, 5, 0);
        int speed = UnitDef.UNITS.get(unit).spd();
        for (int round = 1; round <= 10; round++) {
            assertFirst(attacker, round, true, speed + 25, speed + 10);
            assertFirst(defender, round, false, speed + 10, speed + 25);
        }
        assertTrue(attacker.ownAlive() > defender.ownAlive());
        assertEquals(attacker.ownAlive(), defender.enemyAlive().get(unit));
        assertEquals(defender.ownAlive(), attacker.enemyAlive().get(unit));
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void radarAppliesOnlyAtOpeningAndNeverRestartsOnSkillRounds(String unit) {
        var result = resolve(unit, 2, 2, 3);
        var lowerRadar = resolve(unit, 2, 2, 1);
        var noRadar = resolve(unit, 2, 2, 0);
        int base = UnitDef.UNITS.get(unit).spd() + 10;
        assertFirst(result, 1, false, base, base + 6);
        assertTrue(result.log().get(0).contains("雷达预警：先手+6（仅首回合）"));
        for (int round = 2; round <= 10; round++) {
            assertFirst(result, round, round % 2 == 1, base, base);
            assertFalse(result.log().get(round - 1).contains("雷达预警"));
        }
        assertTrue(result.ownAlive() < noRadar.ownAlive(), "雷达仍应通过首回合顺序带来实际收益");
        assertEquals(lowerRadar.ownAlive(), result.ownAlive());
        assertEquals(lowerRadar.enemyAlive(), result.enemyAlive());
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void attackerResearchCanOvercomeOpeningRadar(String unit) {
        var result = resolve(unit, 5, 0, 2);
        int speed = UnitDef.UNITS.get(unit).spd();
        assertFirst(result, 1, true, speed + 25, speed + 4);
        for (int round = 2; round <= 10; round++) assertFirst(result, round, true, speed + 25, speed);
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void blitzCanChangeLaterInitiativeWhileRadarDoesNotRepeat(String unit) {
        var blitz = new BattleService.TechCtx(Map.of(), Map.of("blitz", 5), 0, 0);
        var result = UnitEquipmentRecon.resolve(unit, 10000, Map.of(unit, 10000), battle, blitz, plain, 0, 0, 10);
        int speed = UnitDef.UNITS.get(unit).spd(), boosted = (int) Math.round(speed * 1.3);
        assertFirst(result, 1, false, boosted, speed + 20);
        for (int round = 2; round <= 10; round++) {
            boolean active = (round - 1) % 3 == 0;
            assertFirst(result, round, active || round % 2 == 1, active ? boosted : speed, speed);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void chiefsAndEquipmentStillContributeToEffectiveSpeed(String unit) {
        var gear = Map.of(UnitEquipmentEffects.key(unit, "battleSpeed"), 15);
        var chief = new com.wargame.model.dto.CommandBonus(1L, "机动司令", 0, 0, 0, 200);
        var equipped = new BattleService.TechCtx(gear, Map.of(), 0, 0, chief);
        var result = UnitEquipmentRecon.resolve(unit, 10000, Map.of(unit, 10000), battle, equipped, plain, 2, 2, 0);
        int speed = UnitDef.UNITS.get(unit).spd(), boosted = (int) Math.round(speed * 1.15 * 1.2) + 10;
        for (int round = 1; round <= 10; round++) assertFirst(result, round, true, boosted, speed + 10);
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void tenLevelBonusesClampAndRadarExpiresAfterFirstRound(String unit) {
        var result = resolve(unit, 10, 10, 10);
        var clamped = resolve(unit, 99, 99, 99);
        int base = UnitDef.UNITS.get(unit).spd() + 50;
        assertFirst(result, 1, false, base, base + 20);
        for (int round = 2; round <= 10; round++) assertFirst(result, round, round % 2 == 1, base, base);
        assertEquals(result.log(), clamped.log());
    }

    @ParameterizedTest @ValueSource(strings = {"scout", "recon_car"})
    void thirdRadarLevelOvercomesOneReconLevelOnlyAtOpening(String unit) {
        int speed = UnitDef.UNITS.get(unit).spd();
        assertFirst(resolve(unit, 3, 2, 2), 1, true, speed + 15, speed + 14);
        var result = resolve(unit, 3, 2, 3);
        assertFirst(result, 1, false, speed + 15, speed + 16);
        for (int round = 2; round <= 10; round++) assertFirst(result, round, true, speed + 15, speed + 10);
    }

    @Test void eliminatedFastEquipmentGroupDoesNotControlLaterInitiative() {
        Map<String, Integer> defense = new LinkedHashMap<>();
        defense.put("scout~fast", 1); defense.put("scout", 9999);
        var gear = Map.of(UnitEquipmentEffects.key("scout~fast", "battleSpeed"), 15);
        var defender = new BattleService.TechCtx(gear, Map.of(), 0, 0);
        var result = UnitEquipmentRecon.resolve("scout", 10000, defense, battle, plain, defender, 2, 0, 0);
        assertEquals(0, result.enemyAlive().get("scout~fast"));
        assertFirst(result, 1, true, 19, 10);
        assertFirst(result, 2, true, 19, 9);
    }
}
