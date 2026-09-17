package com.wargame;

import com.wargame.repository.PlayerRepository;
import com.wargame.service.AccountDeletionScheduler;
import com.wargame.service.AccountService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountDeletionSchedulerTest {
    @Test void failedAccountDoesNotBlockLaterAccountsAndRetriesOnNextSweep() {
        PlayerRepository players = mock(PlayerRepository.class);
        AccountService accounts = mock(AccountService.class);
        when(players.findExpiredAccountIds(eq(0L), anyLong(), any())).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("temporary failure")).doNothing().when(accounts).purgeExpiredAccount(1L);
        AccountDeletionScheduler scheduler = new AccountDeletionScheduler(players, accounts);
        scheduler.cleanExpired();
        scheduler.cleanExpired();
        verify(accounts, times(2)).purgeExpiredAccount(1L);
        verify(accounts, times(2)).purgeExpiredAccount(2L);
    }
}
