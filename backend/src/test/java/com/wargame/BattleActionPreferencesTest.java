package com.wargame;

import com.wargame.model.constants.UnitDef;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.service.BattleActionPreferences;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BattleActionPreferencesTest {
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final BattleActionPreferences preferences = new BattleActionPreferences(players);
    private final Player player = new Player();

    @Test
    void separateOutgoingAndDefendingDefaultsPersistForOfflineRounds() {
        when(players.findById(7L)).thenReturn(Optional.of(player));
        Map<String, String> outgoing = new LinkedHashMap<>(preferences.get(7L).get("outgoing"));
        Map<String, String> defending = new LinkedHashMap<>(preferences.get(7L).get("defending"));
        outgoing.put("rocket", "ADVANCE");
        defending.put("rocket", "HOLD");

        preferences.save(7L, Map.of("outgoing", outgoing, "defending", defending));

        assertEquals("ADVANCE", preferences.get(7L).get("outgoing").get("rocket"));
        assertEquals("HOLD", preferences.get(7L).get("defending").get("rocket"));
        assertEquals(BattleService.CommandAction.ADVANCE,
                preferences.orders(7L, false, Map.of("rocket", 4)).get("rocket").action());
        assertEquals(BattleService.CommandAction.HOLD,
                preferences.orders(7L, true, Map.of("rocket", 4)).get("rocket").action());
        verify(players).save(player);
    }

    @Test
    void invalidOrIncompleteConfigurationIsNotSaved() {
        when(players.findById(7L)).thenReturn(Optional.of(player));
        Map<String, String> outgoing = new LinkedHashMap<>(preferences.get(7L).get("outgoing"));
        Map<String, String> defending = new LinkedHashMap<>(preferences.get(7L).get("defending"));
        outgoing.put("rocket", "ATTACK");
        assertThrows(IllegalArgumentException.class,
                () -> preferences.save(7L, Map.of("outgoing", outgoing, "defending", defending)));
        outgoing.remove("rocket");
        assertThrows(IllegalArgumentException.class,
                () -> preferences.save(7L, Map.of("outgoing", outgoing, "defending", defending)));
        assertEquals(UnitDef.UNITS.size(), defending.size());
        verify(players, never()).save(any());
    }
}
