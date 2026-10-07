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
        when(scope.slot(1L)).thenReturn(0);
        when(cities.findByPlayerIdAndCitySlot(1L, 0)).thenReturn(Optional.of(city));
        when(items.tryConsume(eq(1L), eq("shield"), eq(1), anyLong())).thenReturn(1);
    }

    @Test
    void repeatedUsesExtendActiveShieldAndConsumeEachItem() {
        long originalUntil = System.currentTimeMillis() + EIGHT_HOURS;
        city.setShieldUntil(originalUntil);
        for (int i = 1; i <= 3; i++) {
            var result = depot.useItem(1L, "shield", null, null);
            assertEquals(true, result.get("success"));
            assertEquals(originalUntil + i * EIGHT_HOURS, city.getShieldUntil());
        }
        verify(items, times(3)).tryConsume(eq(1L), eq("shield"), eq(1), anyLong());
        verify(cities, times(3)).save(city);
    }

    @Test
    void expiredOrAbsentShieldStartsFromNow() {
        for (Long previous : new Long[]{null, 0L, System.currentTimeMillis() - EIGHT_HOURS}) {
            city.setShieldUntil(previous);
            long before = System.currentTimeMillis();
            assertEquals(true, depot.useItem(1L, "shield", null, null).get("success"));
            long after = System.currentTimeMillis();
            assertTrue(city.getShieldUntil() >= before + EIGHT_HOURS);
            assertTrue(city.getShieldUntil() <= after + EIGHT_HOURS);
        }
    }

    @Test
    void insufficientInventoryDoesNotExtendShield() {
        long originalUntil = System.currentTimeMillis() + EIGHT_HOURS;
        city.setShieldUntil(originalUntil);
        when(items.tryConsume(eq(1L), eq("shield"), eq(1), anyLong())).thenReturn(0);
        assertEquals(false, depot.useItem(1L, "shield", null, null).get("success"));
        assertEquals(originalUntil, city.getShieldUntil());
        verifyNoInteractions(cities);
    }
}
