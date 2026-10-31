package com.wargame;

import com.wargame.model.entity.CityState;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.CityStateRepository;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.CityScope;
import com.wargame.service.DepotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShieldDurationTest {
    private static final long EIGHT_HOURS = 8L * 3600 * 1000;
    private final PlayerItemRepository items = mock(PlayerItemRepository.class);
    private final CityStateRepository cities = mock(CityStateRepository.class);
    private final CityScope scope = mock(CityScope.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final CityState city = new CityState();
    private final DepotService depot = new DepotService(null, items, null, cities, players, null, null);

    @BeforeEach
    void setUp() {
        when(players.lockById(1L)).thenReturn(Optional.of(new Player()));
        ReflectionTestUtils.setField(depot, "cityScope", scope);
        ReflectionTestUtils.setField(depot, "economy", mock(com.wargame.service.TickService.class));
        when(scope.slot(1L)).thenReturn(0);
        when(cities.findByPlayerIdAndCitySlot(1L, 0)).thenReturn(Optional.of(city));
        when(items.tryConsume(eq(1L), eq("shield"), eq(1), anyLong())).thenReturn(1);
    }

    @ParameterizedTest
    @CsvSource({"shield2h,2", "shield,8", "shield12h,12", "shield24h,24", "shield72h,72"})
    void repeatedUsesExtendActiveShieldAndConsumeEachItem(String itemId, int hours) {
        when(items.tryConsume(eq(1L), eq(itemId), eq(1), anyLong())).thenReturn(1);
        long duration = hours * 3600_000L;
        long originalUntil = System.currentTimeMillis() + EIGHT_HOURS;
        city.setShieldUntil(originalUntil);
        for (int i = 1; i <= 3; i++) {
            var result = depot.useItem(1L, itemId, null, null);
            assertEquals(true, result.get("success"));
            assertEquals(originalUntil + i * duration, city.getShieldUntil());
            assertEquals(city.getShieldUntil(), result.get("shieldUntil"));
            assertTrue(result.get("message").toString().contains(hours + " 小时"));
        }
        verify(items, times(3)).tryConsume(eq(1L), eq(itemId), eq(1), anyLong());
        verify(cities, times(3)).save(city);
    }

    @ParameterizedTest
    @CsvSource({"shield2h,2", "shield,8", "shield12h,12", "shield24h,24", "shield72h,72"})
    void expiredOrAbsentShieldStartsFromNow(String itemId, int hours) {
        when(items.tryConsume(eq(1L), eq(itemId), eq(1), anyLong())).thenReturn(1);
        long duration = hours * 3600_000L;
        for (Long previous : new Long[]{null, 0L, System.currentTimeMillis() - EIGHT_HOURS}) {
            city.setShieldUntil(previous);
            long before = System.currentTimeMillis();
            assertEquals(true, depot.useItem(1L, itemId, null, null).get("success"));
            long after = System.currentTimeMillis();
            assertTrue(city.getShieldUntil() >= before + duration);
            assertTrue(city.getShieldUntil() <= after + duration);
        }
    }

    @ParameterizedTest
    @CsvSource({"shield2h,2", "shield,8", "shield12h,12", "shield24h,24", "shield72h,72"})
    void insufficientInventoryDoesNotExtendShield(String itemId, int hours) {
        long originalUntil = System.currentTimeMillis() + EIGHT_HOURS;
        city.setShieldUntil(originalUntil);
        when(items.tryConsume(eq(1L), eq(itemId), eq(1), anyLong())).thenReturn(0);
        assertEquals(false, depot.useItem(1L, itemId, null, null).get("success"));
        assertEquals(originalUntil, city.getShieldUntil());
        verifyNoInteractions(cities);
    }

    @Test
    void mixedDurationsExtendOnlySelectedCity() {
        CityState branch = new CityState();
        long originalUntil = System.currentTimeMillis() + EIGHT_HOURS;
        city.setShieldUntil(originalUntil);
        branch.setShieldUntil(originalUntil);
        when(scope.slot(1L)).thenReturn(1);
        when(cities.findByPlayerIdAndCitySlot(1L, 1)).thenReturn(Optional.of(branch));
        for (String itemId : new String[]{"shield2h", "shield", "shield12h", "shield24h", "shield72h"}) {
            when(items.tryConsume(eq(1L), eq(itemId), eq(1), anyLong())).thenReturn(1);
            assertEquals(true, depot.useItem(1L, itemId, null, null).get("success"));
        }
        assertEquals(originalUntil + 118 * 3600_000L, branch.getShieldUntil());
        assertEquals(originalUntil, city.getShieldUntil());
        verify(cities, never()).save(city);
    }

    @Test
    void unknownShieldDoesNotConsumeInventory() {
        assertEquals(false, depot.useItem(1L, "shield999h", null, null).get("success"));
        verifyNoInteractions(items, cities);
    }
}
