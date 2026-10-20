package com.wargame;

import com.wargame.model.entity.CityState;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.Resources;
import com.wargame.repository.CityStateRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.service.CityScope;
import com.wargame.service.CityService;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CityShieldServiceTest {
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final ResourcesRepository resources = mock(ResourcesRepository.class);
    private final CityStateRepository states = mock(CityStateRepository.class);
    private final CityScope scope = mock(CityScope.class);
    private final CityService service = new CityService(players, null, null, resources, null, states, null, scope, null);
    private final Resources wallet = new Resources();
    private static final long NOW = 1_000_000L;
    private static final long DURATION = 8L * 3600 * 1000;

    @BeforeEach
    void setUp() {
        when(players.lockById(1L)).thenReturn(Optional.of(new Player()));
        wallet.setDiamond(1000);
        when(scope.wallet(1L)).thenReturn(wallet);
        for (long id : List.of(10L, 20L)) {
            PlayerCity city = new PlayerCity();
            city.setId(id);
            city.setOwnerId(1L);
            city.setCitySlot(id == 10L ? 0 : 1);
            city.setName("城市" + id);
            when(scope.requireOwned(1L, id, true)).thenReturn(city);
        }
    }

    @Test
    void shieldsChargeUniqueCitiesAndExtendIndependentStoredDeadlines() throws Exception {
        CityState existing = new CityState();
        existing.setPlayerId(1L);
        existing.setCitySlot(1);
        existing.setShieldUntil(NOW + DURATION);
        when(states.findByPlayerIdAndCitySlot(1L, 1)).thenReturn(Optional.of(existing));
        try (var ignored = GameClock.use(() -> NOW)) {
            var result = service.activateShields(1L, List.of(10L, 20L, 10L));
            assertEquals(2, result.get("count"));
            assertEquals(400, result.get("cost"));
            assertEquals(600, wallet.getDiamond());
            assertEquals(NOW + 2 * DURATION, existing.getShieldUntil());
            var saved = org.mockito.ArgumentCaptor.forClass(CityState.class);
            verify(states, times(2)).save(saved.capture());
            CityState main = saved.getAllValues().stream().filter(s -> s.getCitySlot() == 0).findFirst().orElseThrow();
            assertEquals(1L, main.getPlayerId());
            assertEquals(NOW + DURATION, main.getShieldUntil());
        }
    }

    @Test
    void insufficientDiamondsDoNotWriteAnyCityStateOrChargeWallet() {
        wallet.setDiamond(399);
        assertThrows(IllegalArgumentException.class, () -> service.activateShields(1L, List.of(10L, 20L)));
        assertEquals(399, wallet.getDiamond());
        verifyNoInteractions(states, resources);
    }

    @Test
    void oneUnownedCityRejectsTheWholeSelectionBeforeAnyCharge() {
        when(scope.requireOwned(1L, 99L, true)).thenThrow(new IllegalArgumentException("城市不存在或不属于你"));
        assertThrows(IllegalArgumentException.class, () -> service.activateShields(1L, List.of(10L, 99L)));
        assertEquals(1000, wallet.getDiamond());
        verifyNoInteractions(states, resources);
    }
}
