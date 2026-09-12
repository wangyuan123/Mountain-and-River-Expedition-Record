package com.wargame;

import com.wargame.repository.PlayerRepository;
import com.wargame.service.TickScheduler;
import com.wargame.service.TickService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TickSchedulerTest {
    @Test
    void batchesContinueAcrossRunsAndAConflictDoesNotStarveLaterPlayers() {
        PlayerRepository players = mock(PlayerRepository.class);
        TickService ticks = mock(TickService.class);
        when(players.findDuePlayerIds(eq(0L), anyLong(), any(Pageable.class))).thenReturn(List.of(1L, 2L));
        when(players.findDuePlayerIds(eq(2L), anyLong(), any(Pageable.class))).thenReturn(List.of(3L));
        doThrow(new OptimisticLockingFailureException("conflict")).when(ticks).tick(1L);
        TickScheduler scheduler = new TickScheduler(players, ticks, 5000, 2, 1);
        scheduler.tickAll();
        scheduler.tickAll();
        verify(ticks).tick(1L);
        verify(ticks).tick(2L);
        verify(ticks).tick(3L);
        verify(players, never()).findAll();
        scheduler.tickAll();
        verify(players, times(2)).findDuePlayerIds(eq(0L), anyLong(), any(Pageable.class));
    }
}
