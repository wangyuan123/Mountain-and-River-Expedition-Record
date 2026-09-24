package com.wargame;

import com.wargame.repository.BattleSessionRepository;
import com.wargame.service.MarchService;
import com.wargame.service.TacticalBattleScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TacticalBattleSchedulerTest {
    @Test
    void givesBrowserTimeToSubmitOrdersBeforeAutoResolving() {
        BattleSessionRepository sessions = mock(BattleSessionRepository.class);
        MarchService marches = mock(MarchService.class);
        when(sessions.findDueRoundIds(anyLong(), any(Pageable.class))).thenReturn(List.of(12L));
        TacticalBattleScheduler scheduler = new TacticalBattleScheduler(sessions, marches, 10);
        long before = System.currentTimeMillis();

        scheduler.resolveTimedOutRounds();

        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(sessions).findDueRoundIds(cutoff.capture(), any(Pageable.class));
        assertTrue(cutoff.getValue() >= before - 2_000L);
        assertTrue(cutoff.getValue() <= System.currentTimeMillis() - 2_000L);
        verify(marches).processTimedOutTacticalBattle(eq(12L), anyLong());
    }
}
