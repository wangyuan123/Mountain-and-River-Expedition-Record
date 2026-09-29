package com.wargame.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServerDatabaseGuardTest {
    @Test
    void emptyDatabaseBindsToItsFirstRegion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = new TransactionTemplate(mock(PlatformTransactionManager.class));
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(null);
        new ServerDatabaseGuard(jdbc, new GameServerIdentity("jiangsu-2", "江苏二区"), transactions).afterSingletonsInstantiated();
        verify(jdbc).update(anyString(), eq("jiangsu-2"));
    }

    @Test
    void occupiedDatabaseRejectsAnotherRegion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = new TransactionTemplate(mock(PlatformTransactionManager.class));
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("jiangsu-1");
        assertThrows(IllegalStateException.class,
                () -> new ServerDatabaseGuard(jdbc, new GameServerIdentity("jiangsu-2", "江苏二区"), transactions).afterSingletonsInstantiated());
        verify(jdbc, never()).update(anyString(), eq("jiangsu-2"));
    }
}
