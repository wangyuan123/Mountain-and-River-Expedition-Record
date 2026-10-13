package com.wargame;

import com.wargame.model.constants.*;
import com.wargame.model.dto.*;
import com.wargame.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UnitEquipmentRulesTest {
    private Map<String, Integer> gear(String unit, String... keys) {
        Map<String, String> slots = new LinkedHashMap<>();
        for (String key : keys) slots.put(UnitEquipmentDef.ITEMS.get(key).slot(), key);
        return UnitEquipmentEffects.snapshot(Map.of(unit, slots));
    }
    @Test void catalogHasSeventeenTypesExplicitRecipesAndNoMotor() {
        assertEquals(68, UnitEquipmentDef.ITEMS.size());
        assertEquals(17, UnitEquipmentDef.ITEMS.values().stream().map(UnitEquipmentDef::unit).distinct().count());
        assertFalse(UnitEquipmentDef.ITEMS.values().stream().anyMatch(d -> "motor".equals(d.unit())));
        for (String unit : UnitDef.UNITS.keySet()) {
            assertEquals(new HashSet<>(UnitEquipmentDef.SLOTS), UnitEquipmentDef.ITEMS.values().stream().filter(d -> d.unit().equals(unit)).map(UnitEquipmentDef::slot).collect(java.util.stream.Collectors.toSet()));
        }
        assertEquals(Map.of("steel", 300, "oil", 0, "rare", 0), UnitEquipmentDef.ITEMS.get("ue_infantry_helmet").cost());
        assertEquals(Map.of("steel", 600, "oil", 100, "rare", 30), UnitEquipmentDef.ITEMS.get("ue_infantry_radio").cost());
        assertEquals(3, UnitEquipmentDef.ITEMS.get("ue_htank_armor").level());
        assertEquals(Map.of("steel", 1440, "oil", 80, "rare", 20), UnitEquipmentDef.ITEMS.get("ue_ltank_armor").cost());
        assertEquals("precision", UnitEquipmentDef.ITEMS.get("ue_destroyer_firecontrol").recipe());
    }
    @Test void additiveModifiersScenesAndHistoricalSnapshotAreStable() {
        var s = gear("infantry", "ue_infantry_vest", "ue_infantry_lmg", "ue_infantry_radio");
        assertEquals(1.15, UnitEquipmentEffects.attack(s, "infantry", "infantry"), 1e-9);
        assertEquals(1.08, UnitEquipmentEffects.mul(s, "infantry", "defense"), 1e-9);
        assertEquals(1.12, UnitEquipmentEffects.mul(s, "infantry", "hp"), 1e-9);
        assertEquals(.99, UnitEquipmentEffects.speed(s, "infantry", true), 1e-9);
        assertEquals(.94, UnitEquipmentEffects.speed(s, "infantry", false), 1e-9);
        assertEquals(1, UnitEquipmentEffects.attack(s, "ltank", "infantry"));
        var truck = gear("truck", "ue_truck_cargo", "ue_truck_economy");
        assertEquals(.95, UnitEquipmentEffects.mul(truck, "truck", "oil"), 1e-9);
        assertEquals(1.2, UnitEquipmentEffects.mul(truck, "truck", "load"), 1e-9);
        assertEquals(1, UnitEquipmentEffects.speed(null, "truck", false));
        gear("infantry", "ue_infantry_helmet");
        assertEquals(.94, UnitEquipmentEffects.speed(s, "infantry", false), 1e-9);
    }
    @Test void conditionalEffectsMatchBaseTypesAndKeepOriginalCounters() {
        var tank = gear("ltank", "ue_ltank_armor", "ue_ltank_liner");
        Map<String, Integer> alias = new LinkedHashMap<>(); UnitEquipmentEffects.copyProfile(alias, "ltank~1", UnitEquipmentEffects.profile(tank, "ltank"));
        assertEquals(.85, UnitEquipmentEffects.damage(alias, "ltank~1", "rocket~2"), 1e-9);
        assertEquals(1, UnitEquipmentEffects.damage(alias, "ltank~1", "assault"));
        assertEquals(3, BattleRules.multiplier("rocket", "ltank~1"));
        assertEquals(2.5, BattleRules.multiplier("armored~2", "fighter~4"));
        assertEquals(1, BattleRules.multiplier("armored~2", "scout"));
        assertEquals(1.15, UnitEquipmentEffects.attack(gear("destroyer", "ue_destroyer_firecontrol", "ue_destroyer_sonar"), "destroyer", "sub~1"), 1e-9);
    }
    @Test void emptyEquipmentAndAliasedTroopsRetainBattleSemantics() {
        var a = Map.of("infantry", 30); var d = Map.of("infantry", 30);
        BattleResult old = new BattleService(42).startWorldDispatch(a, d, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, "conquer", Map.of(), 0, true);
        BattleResult empty = new BattleService(42).startWorldDispatch(a, Map.of("infantry~1", 30), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, "conquer", Map.of(), 0, true);
        assertEquals(old.isWin(), empty.isWin());
        assertEquals(old.getSurvivorAttacker(), empty.getSurvivorAttacker());
        assertEquals(old.getSurvivorDefender(), UnitEquipmentEffects.aggregate(empty.getSurvivorDefender()));
        assertEquals(BattlePrestige.unitValue("infantry"), BattlePrestige.unitValue("infantry~1"));
    }
    @Test void actualBattleUsesDefenseHpAttackAndRocketReduction() {
        var a = Map.of("rocket", 10); var d = Map.of("ltank", 100);
        var tank = gear("ltank", "ue_ltank_armor", "ue_ltank_liner");
        var round = new BattleService(7).resolveWorldRound(a, d, Map.of("rocket", 0), Map.of("ltank", 100), 100,
                Map.of(), tank, Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 1, 0,
                Map.of("rocket", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)), Map.of("ltank", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));
        assertTrue(round.log().contains("装备减伤15%"));
        assertTrue(round.log().contains("倍率×3.0"));
        var ordinary = new BattleService(7).resolveWorldRound(a, d, Map.of("rocket", 0), Map.of("ltank", 100), 100,
                Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 1, 0,
                Map.of("rocket", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)), Map.of("ltank", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)));
        assertTrue(round.defenderArmy().get("ltank") >= ordinary.defenderArmy().get("ltank"));
    }
    @Test void reconEmptyEquipmentMatchesExistingOneProfileFormulaAndSeparatesProfiles() {
        var r = UnitEquipmentRecon.resolve("scout", 100, Map.of("scout", 100), Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, 0);
        assertEquals(59, r.ownAlive()); assertEquals(57, r.enemyAlive().get("scout"));
        var s = gear("scout", "ue_scout_armor", "ue_scout_radio");
        Map<String, Integer> mixed = new LinkedHashMap<>(); UnitEquipmentEffects.copyProfile(mixed, "scout~1", UnitEquipmentEffects.profile(s, "scout"));
        var defended = UnitEquipmentRecon.resolve("scout", 100, Map.of("scout", 50, "scout~1", 50), Map.of(), mixed, CommandBonus.NONE, CommandBonus.NONE, 0);
        assertEquals(Set.of("scout", "scout~1"), defended.enemyAlive().keySet());
        assertTrue(defended.enemyAlive().get("scout~1") > 0);
    }
}
