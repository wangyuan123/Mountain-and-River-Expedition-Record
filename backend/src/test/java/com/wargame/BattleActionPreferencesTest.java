package com.wargame;

import com.wargame.model.constants.UnitDef;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.service.BattleActionPreferences;
import com.wargame.service.BattleService;
import com.wargame.service.ArmyService;
import com.wargame.repository.MarchRepository;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.model.entity.PlayerCity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BattleActionPreferencesTest {
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final ArmyService armyService = mock(ArmyService.class);
    private final MarchRepository marches = mock(MarchRepository.class);
    private final BattleSessionRepository battles = mock(BattleSessionRepository.class);
    private final PlayerCityRepository cities = mock(PlayerCityRepository.class);
    private final BattleActionPreferences preferences = new BattleActionPreferences(players, armyService, marches, battles, cities);
    private final Player player = new Player();

    private BattleActionPreferences.Preferences configured(Map<String, Object> army) {
        when(players.findById(7L)).thenReturn(Optional.of(player));
        when(armyService.sortieCap(7L)).thenReturn(37500);
        var current = preferences.get(7L);
        return new BattleActionPreferences.Preferences(current.outgoing(), current.defending(), army);
    }

    @Test
    void attackingMarchLocksEvenBeforeBattleAndUnlocksAfterReturn() {
        var request = configured(null);
        when(marches.existsActiveAttack(7L)).thenReturn(true);
        assertTrue(preferences.get(7L).locked());
        assertThrows(IllegalArgumentException.class, () -> preferences.save(7L, request));
        verify(players, never()).save(any());
        when(marches.existsActiveAttack(7L)).thenReturn(false);
        assertFalse(preferences.get(7L).locked());
        preferences.save(7L, request);
        verify(players).save(any());
    }

    @Test
    void defendingBattleInAnyOwnedCityLocksTactics() {
        var request = configured(null);
        PlayerCity city = new PlayerCity();
        city.setId(42L);
        when(cities.findByOwnerId(7L)).thenReturn(java.util.List.of(city));
        when(battles.existsDefendingCityBattle(java.util.List.of("42"))).thenReturn(true);
        assertTrue(preferences.get(7L).locked());
        assertThrows(IllegalArgumentException.class, () -> preferences.save(7L, request));
        verify(players, never()).save(any());
    }

    @Test
    void attackerBattleSessionLocksEvenWithoutMarchRow() {
        var request = configured(null);
        when(battles.existsByPlayerId(7L)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> preferences.save(7L, request));
    }

    @Test
    void manualSortiePersistsAndOnlySelectsAvailableConfiguredTroops() {
        preferences.save(7L, configured(Map.of("infantry", 25000, "rocket", 12500)));
        assertEquals(Map.of("infantry", 25000, "rocket", 12500), preferences.get(7L).sortieArmy());
        assertEquals(37500, preferences.get(7L).sortieCap());
        assertEquals(Map.of("infantry", 20000, "rocket", 12500), preferences.selectSortieArmy(7L,
                Map.of("infantry", 20000, "rocket", 50000, "htank", 10000)));
    }

    @Test
    void automaticAndManualZeroHaveDifferentMeanings() {
        preferences.save(7L, configured(null));
        assertNull(preferences.get(7L).sortieArmy());
        assertEquals(Map.of("infantry", 18750, "rocket", 18750), preferences.selectSortieArmy(7L,
                Map.of("infantry", 50000, "rocket", 50000, "bunker", 100000)));
        preferences.save(7L, configured(Map.of()));
        assertTrue(preferences.selectSortieArmy(7L, Map.of("infantry", 50000)).isEmpty());
        assertNotNull(preferences.get(7L).sortieArmy());
    }

    @Test
    void millionGarrisonCannotExceedFourHundredThousandDefenseCap() {
        var request = configured(Map.of("infantry", 300000, "rocket", 100000));
        when(armyService.sortieCap(7L)).thenReturn(400000);
        var garrison = Map.of("infantry", 600000, "rocket", 400000);
        preferences.save(7L, request);
        assertEquals(Map.of("infantry", 300000, "rocket", 100000), preferences.selectSortieArmy(7L, garrison));
        assertThrows(IllegalArgumentException.class, () -> preferences.save(7L,
                new BattleActionPreferences.Preferences(request.outgoing(), request.defending(),
                        Map.of("infantry", 300000, "rocket", 100001))));
        assertEquals(Map.of("infantry", 300000, "rocket", 100000), preferences.get(7L).sortieArmy());
        preferences.save(7L, new BattleActionPreferences.Preferences(request.outgoing(), request.defending(), null));
        assertEquals(Map.of("infantry", 240000, "rocket", 160000), preferences.selectSortieArmy(7L, garrison));
        assertEquals(Map.of("infantry", 600000, "rocket", 400000), garrison);
    }

    @Test
    void changedCityCapacityScalesCompositionAndAllocatesRemainderDeterministically() {
        preferences.save(7L, configured(Map.of("infantry", 10000, "rocket", 10000, "htank", 10000)));
        when(armyService.sortieCap(7L)).thenReturn(5);
        assertEquals(Map.of("htank", 2, "infantry", 2, "rocket", 1), preferences.selectSortieArmy(7L,
                Map.of("infantry", 10000, "rocket", 10000, "htank", 10000)));
    }

    @Test
    void invalidSortieNeverReplacesSavedActionsOrArmy() {
        player.setOutgoingBattleActions("{}");
        player.setSortieArmy("{}");
        for (Object invalid : java.util.List.of(-1, 1.5, "10", 2147483648L, Double.NaN, Double.POSITIVE_INFINITY)) {
            var request = configured(Map.of("infantry", invalid));
            assertThrows(IllegalArgumentException.class, () -> preferences.save(7L, request));
        }
        var nullCount = new LinkedHashMap<String, Object>();
        nullCount.put("infantry", null);
        assertThrows(IllegalArgumentException.class, () -> preferences.save(7L, configured(nullCount)));
        assertThrows(IllegalArgumentException.class, () -> preferences.save(7L, configured(Map.of("bunker", 1))));
        assertThrows(IllegalArgumentException.class, () -> preferences.save(7L, configured(Map.of("unknown", 0))));
        assertThrows(IllegalArgumentException.class, () -> preferences.save(7L, configured(Map.of("infantry", 37501))));
        assertThrows(IllegalArgumentException.class, () -> preferences.save(7L,
                configured(Map.of("infantry", Integer.MAX_VALUE, "rocket", Integer.MAX_VALUE))));
        assertEquals("{}", player.getSortieArmy());
        assertEquals("{}", player.getOutgoingBattleActions());
        verify(players, never()).save(any());
    }

    @Test
    void separateOutgoingAndDefendingDefaultsPersistForOfflineRounds() {
        when(players.findById(7L)).thenReturn(Optional.of(player));
        Map<String, String> outgoing = new LinkedHashMap<>(preferences.get(7L).outgoing());
        Map<String, String> defending = new LinkedHashMap<>(preferences.get(7L).defending());
        outgoing.put("rocket", "ADVANCE");
        defending.put("rocket", "HOLD");

        preferences.save(7L, new BattleActionPreferences.Preferences(outgoing, defending, null));

        assertEquals("ADVANCE", preferences.get(7L).outgoing().get("rocket"));
        assertEquals("HOLD", preferences.get(7L).defending().get("rocket"));
        assertEquals(BattleService.CommandAction.ADVANCE,
                preferences.orders(7L, false, Map.of("rocket", 4)).get("rocket").action());
        assertEquals(BattleService.CommandAction.HOLD,
                preferences.orders(7L, true, Map.of("rocket", 4)).get("rocket").action());
        verify(players).save(player);
    }

    @Test
    void invalidOrIncompleteConfigurationIsNotSaved() {
        when(players.findById(7L)).thenReturn(Optional.of(player));
        Map<String, String> outgoing = new LinkedHashMap<>(preferences.get(7L).outgoing());
        Map<String, String> defending = new LinkedHashMap<>(preferences.get(7L).defending());
        outgoing.put("rocket", "ATTACK");
        assertThrows(IllegalArgumentException.class,
                () -> preferences.save(7L, new BattleActionPreferences.Preferences(outgoing, defending, null)));
        outgoing.remove("rocket");
        assertThrows(IllegalArgumentException.class,
                () -> preferences.save(7L, new BattleActionPreferences.Preferences(outgoing, defending, null)));
        assertEquals(UnitDef.UNITS.size(), defending.size());
        verify(players, never()).save(any());
    }
}
