package com.wargame;

import com.wargame.model.entity.TacticalBattleOutcome;
import com.wargame.repository.TacticalBattleOutcomeRepository;
import com.wargame.service.TacticalBattleOutcomeService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TacticalBattleOutcomeTest {
    @Test
    void originalParticipantsKeepTheirOwnOutcomeAfterMarchOrOwnershipChanges() {
        TacticalBattleOutcomeRepository repository = mock(TacticalBattleOutcomeRepository.class);
        TacticalBattleOutcomeService service = new TacticalBattleOutcomeService(repository);
        service.save(3L, 10L, 20L, Map.of("finished", true, "side", "attacker", "result", Map.of("win", true)),
                Map.of("finished", true, "side", "defender", "result", Map.of("win", false)));
        ArgumentCaptor<TacticalBattleOutcome> row = ArgumentCaptor.forClass(TacticalBattleOutcome.class);
        verify(repository).save(row.capture());
        when(repository.findById(3L)).thenReturn(Optional.of(row.getValue()));
        assertEquals("attacker", service.find(10L, 3L).get("side"));
        assertEquals("defender", service.find(20L, 3L).get("side"));
        assertEquals(false, ((Map<?, ?>) service.find(20L, 3L).get("result")).get("win"));
        assertThrows(IllegalArgumentException.class, () -> service.find(30L, 3L));
        assertTrue(row.getValue().getExpiresAt() > System.currentTimeMillis() + 29L * 86400000);
    }

    @Test
    void expiredOutcomesAreNotReturnedAndCleanupUsesRealTime() {
        TacticalBattleOutcomeRepository repository = mock(TacticalBattleOutcomeRepository.class);
        TacticalBattleOutcomeService service = new TacticalBattleOutcomeService(repository);
        TacticalBattleOutcome row = new TacticalBattleOutcome();
        row.setMarchId(3L); row.setAttackerId(10L); row.setExpiresAt(System.currentTimeMillis() - 1);
        row.setAttackerView("{\"finished\":true}");
        when(repository.findById(3L)).thenReturn(Optional.of(row));
        assertNull(service.find(10L, 3L));
        assertNull(service.find(10L, 4L));
        service.expire();
        verify(repository).deleteByExpiresAtLessThanEqual(anyLong());
    }
}
