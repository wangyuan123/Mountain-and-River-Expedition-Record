package com.wargame;

import com.wargame.model.constants.StratagemRules;
import com.wargame.model.entity.CityState;
import com.wargame.model.entity.Player;
import com.wargame.repository.CityStateRepository;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.service.CityScope;
import com.wargame.service.DepotService;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarchOrderDurationTest {
    private final PlayerItemRepository items = mock(PlayerItemRepository.class);
    private final CityStateRepository cities = mock(CityStateRepository.class);
    private final CityScope scope = mock(CityScope.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final CityState city = new CityState();
    private final DepotService depot = new DepotService(null, items, null, cities, players, null, null);

    @BeforeEach void setUp() {
        when(players.lockById(1L)).thenReturn(Optional.of(new Player()));
        ReflectionTestUtils.setField(depot, "cityScope", scope);
        when(scope.slot(1L)).thenReturn(0);
        when(cities.findByPlayerIdAndCitySlot(1L, 0)).thenReturn(Optional.of(city));
        when(items.tryConsume(eq(1L), anyString(), eq(1), anyLong())).thenReturn(1);
    }

    @Test void higherTierReplacesLowerAndLowerTierCannotRefreshHigher() {
        assertEquals(true, depot.useItem(1L, "basicMarchOrd", null, null).get("success"));
        assertEquals(1.1, city.marchSpeedMultiplier(GameClock.now()), 0.000001);
        assertEquals(true, depot.useItem(1L, "advancedMarchOrd", null, null).get("success"));
        assertEquals(1.25, city.marchSpeedMultiplier(GameClock.now()), 0.000001);
        assertEquals(true, depot.useItem(1L, "marchOrd", null, null).get("success"));
        long until = city.getMarchBoostUntil();
        assertEquals(false, depot.useItem(1L, "basicMarchOrd", null, null).get("success"));
        assertEquals(false, depot.useItem(1L, "advancedMarchOrd", null, null).get("success"));
        assertEquals(until, city.getMarchBoostUntil());
        assertEquals(50, city.getMarchBoostPercent());
        verify(items, times(1)).tryConsume(eq(1L), eq("basicMarchOrd"), eq(1), anyLong());
        verify(items, times(1)).tryConsume(eq(1L), eq("advancedMarchOrd"), eq(1), anyLong());
    }

    @Test void expirationAllowsLowerTierAndSameTierRefreshesWithoutStackingHours() {
        city.setMarchBoostPercent(50);
        city.setMarchBoostUntil(GameClock.now() - 1);
        assertEquals(1.0, city.marchSpeedMultiplier(GameClock.now()));
        assertEquals(true, depot.useItem(1L, "basicMarchOrd", null, null).get("success"));
        city.setMarchBoostUntil(GameClock.now() + 60_000L);
        long before = GameClock.now();
        assertEquals(true, depot.useItem(1L, "basicMarchOrd", null, null).get("success"));
        assertTrue(city.getMarchBoostUntil() >= before + 3600_000L);
        assertTrue(city.getMarchBoostUntil() <= GameClock.now() + 3600_000L);
        assertEquals(10, city.getMarchBoostPercent());
    }

    @Test void insufficientInventoryDoesNotChangeActiveEffect() {
        city.setMarchBoostPercent(10);
        long until = GameClock.now() + 60_000L;
        city.setMarchBoostUntil(until);
        when(items.tryConsume(eq(1L), eq("marchOrd"), eq(1), anyLong())).thenReturn(0);
        assertEquals(false, depot.useItem(1L, "marchOrd", null, null).get("success"));
        assertEquals(until, city.getMarchBoostUntil());
        assertEquals(10, city.getMarchBoostPercent());
        verify(cities, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"basicMarchOrd,90000,60000", "advancedMarchOrd,75000,45000", "marchOrd,50000,30000"})
    void knowledgeCapsAndMinimumRemainingTimeApplyToEachTier(String key, long baseRemaining, long cappedRemaining) {
        assertEquals(baseRemaining, StratagemRules.acceleratedMarchRemaining(100_000, 0, 30_000, key));
        assertEquals(cappedRemaining, StratagemRules.acceleratedMarchRemaining(100_000, 999, 30_000, key));
        assertEquals(30_000, StratagemRules.acceleratedMarchRemaining(31_000, 999, 30_000, key));
        assertEquals(baseRemaining, StratagemRules.acceleratedMarchRemaining(100_000, -100, 30_000, key));
    }

    @Test void legacyBoostWithoutTierKeepsFiftyPercent() {
        city.setMarchBoostPercent(null);
        city.setMarchBoostUntil(GameClock.now() + 60_000L);
        assertEquals(1.5, city.marchSpeedMultiplier(GameClock.now()));
    }
}
