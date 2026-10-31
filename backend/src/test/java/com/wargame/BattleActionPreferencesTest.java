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
        when(players.lockById(7L)).thenReturn(Optional.of(player));
        when(armyService.armyCapAt(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyLong())).thenReturn(25000);
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
    void legacyCountsAreIgnoredAndActionsStillPersist() {
        player.setSortieArmy("{\"infantry\":1}");
        player.setMobilizationSortieArmy("{}");
        var request = configured(Map.of("infantry", 0));
        preferences.save(7L, request);
        assertNull(preferences.get(7L).sortieArmy());
        assertEquals(25000, preferences.get(7L).sortieCap()); // 兼容字段仅表示出征上限。
        assertEquals(Map.of("infantry", 12000, "rocket", 50000, "htank", 10000), preferences.selectSortieArmy(7L,
                Map.of("infantry", 12000, "rocket", 50000, "htank", 10000)));
        assertEquals("{\"infantry\":1}", player.getSortieArmy());
        assertEquals("{}", player.getMobilizationSortieArmy());
    }

    @Test
    void emptyLegacyPresetAndNoPresetBothDefendWithAllAvailableTroops() {
        for (String preset : java.util.Arrays.asList(null, "{}", "{\"infantry\":0}")) {
            player.setSortieArmy(preset);
            assertEquals(Map.of("infantry", 50000, "rocket", 50000), preferences.selectSortieArmy(7L,
                    Map.of("infantry", 50000, "rocket", 50000, "bunker", 100000)));
        }
    }

    @Test
    void millionGarrisonDefendsInFullEvenWhenDispatchCapDrops() {
        configured(null);
        var garrison = Map.of("infantry", 600000, "rocket", 400000);
        for (int cap : new int[] {400000, 25000, 5}) {
            when(armyService.armyCapAt(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyLong())).thenReturn(cap);
            assertEquals(garrison, preferences.selectSortieArmy(7L, garrison));
        }
        assertEquals(Map.of("infantry", 600000, "rocket", 400000), garrison);
    }

    @Test
    void logisticsAlsoParticipateButInvalidCountsAndFortsAreExcluded() {
        var available = new LinkedHashMap<String, Integer>();
        available.put("truck", 1000000);
        available.put("transport", 1000000);
        available.put("infantry", 0);
        available.put("rocket", -1);
        available.put("htank", null);
        available.put("bunker", 100);
        available.put("unknown", 1);
        assertEquals(Map.of("truck", 1000000, "transport", 1000000), preferences.selectSortieArmy(7L, available));
    }

    @Test
    void oldMobilizationKeyNoLongerBlocksSavingActions() {
        var request = configured(Map.of("infantry", 1000000));
        preferences.save(7L, new BattleActionPreferences.Preferences(request.outgoing(), request.defending(), request.sortieArmy(), "mobilization:old"));
        assertNull(preferences.get(7L).sortieArmy());
        verify(players).save(player);
    }

    @Test
    void separateOutgoingAndDefendingDefaultsPersistForOfflineRounds() {
        when(players.findById(7L)).thenReturn(Optional.of(player));
        when(players.lockById(7L)).thenReturn(Optional.of(player));
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
        when(players.lockById(7L)).thenReturn(Optional.of(player));
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
