package com.wargame.service;

import com.wargame.util.GameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 用独立数据库与可控单调时钟模拟强杀、长停机及数据库故障，无需真实等待。 */
class GameClockRuntimeTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private final AtomicLong monotonic = new AtomicLong();
    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);

    @BeforeEach void database() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:clock-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V106__persistent_game_clock.sql"))
                .execute(dataSource);
        jdbc.execute("CREATE TABLE players (last_tick BIGINT)");
    }

    private GameClockRuntime start() {
        var runtime = new GameClockRuntime(jdbc, transactions, monotonic::get, wall::get);
        runtime.afterSingletonsInstantiated();
        return runtime;
    }

    @Test void restartAfterTwoDaysPreservesEveryActivitiesRemainingDuration() throws Exception {
        long[] deadlines;
        var first = start();
        try {
            monotonic.addAndGet(1_000_000_000L); first.advance();
            long now = GameClock.now();
            deadlines = new long[] {now + 15_000, now + 30_000, now + 60_000, now + 3600_000, now + 86_400_000};
            // 任意活动只能读到已经提交的时间，强杀前未提交的零头不可能触发结算。
            monotonic.addAndGet(900_000_000L);
            assertEquals(now, GameClock.now());
        } finally { first.destroy(); }
        wall.addAndGet(2 * 86_400_000L);
        monotonic.addAndGet(2 * 86_400_000_000_000L);
        var restarted = start();
        try {
            assertArrayEquals(new long[] {15_000, 30_000, 60_000, 3600_000, 86_400_000},
                    java.util.Arrays.stream(deadlines).map(end -> end - GameClock.now()).toArray());
            monotonic.addAndGet(1_000_000_000L); restarted.advance();
            assertEquals(14_000, deadlines[0] - GameClock.now());
        } finally { restarted.destroy(); }
    }

    @Test void firstDeploymentUsesLegacySettlementPointInsteadOfCatchingUpDowntime() throws Exception {
        long saved = wall.get() - 3600_000;
        jdbc.update("INSERT INTO players VALUES (?)", saved);
        var runtime = start();
        try { assertEquals(saved, GameClock.now()); } finally { runtime.destroy(); }
    }

    @Test void databaseFailureAndLongProcessSuspensionDoNotAdvanceTime() throws Exception {
        var runtime = start();
        try {
            long before = GameClock.now();
            jdbc.execute("ALTER TABLE game_clock RENAME TO clock_unavailable");
            monotonic.addAndGet(1_000_000_000L);
            assertThrows(RuntimeException.class, runtime::advance);
            assertEquals(before, GameClock.now());
            jdbc.execute("ALTER TABLE clock_unavailable RENAME TO game_clock");
            monotonic.addAndGet(1_000_000_000L); runtime.advance();
            assertEquals(before + 1_000, GameClock.now());
            monotonic.addAndGet(3_600_000_000_000L); runtime.advance();
            assertEquals(before + 1_000, GameClock.now());
            monotonic.addAndGet(1_000_000_000L); runtime.advance();
            assertEquals(before + 2_000, GameClock.now());
        } finally { runtime.destroy(); }
    }

    @Test void wallClockChangesCannotSkipOrReverseGameTime() throws Exception {
        var runtime = start();
        try {
            long before = GameClock.now();
            wall.addAndGet(86_400_000L);
            monotonic.addAndGet(1_000_000_000L); runtime.advance();
            assertEquals(before + 1_000, GameClock.now());
            wall.addAndGet(-2 * 86_400_000L);
            monotonic.addAndGet(1_000_000_000L); runtime.advance();
            assertEquals(before + 2_000, GameClock.now());
        } finally { runtime.destroy(); }
    }

    @Test void blockedFailedCheckpointDoesNotLeakItsWaitingTimeIntoRecovery() throws Exception {
        var failingJdbc = org.mockito.Mockito.spy(jdbc);
        var runtime = new GameClockRuntime(failingJdbc, transactions, monotonic::get, wall::get);
        runtime.afterSingletonsInstantiated();
        String query = "SELECT game_time, owner_token, heartbeat_at FROM game_clock WHERE singleton_id = 1 FOR UPDATE";
        try {
            long before = GameClock.now();
            org.mockito.Mockito.doAnswer(invocation -> {
                monotonic.addAndGet(3_000_000_000L);
                throw new org.springframework.dao.DataAccessResourceFailureException("simulated database timeout");
            }).when(failingJdbc).queryForMap(query);
            monotonic.addAndGet(1_000_000_000L);
            assertThrows(RuntimeException.class, runtime::advance);
            assertEquals(before, GameClock.now());
            org.mockito.Mockito.doCallRealMethod().when(failingJdbc).queryForMap(query);
            monotonic.addAndGet(1_000_000_000L); runtime.advance();
            assertEquals(before + 1_000, GameClock.now());
        } finally { runtime.destroy(); }
    }

    @Test void twoInstancesDoNotMultiplyGameSpeed() throws Exception {
        var first = start();
        var second = start();
        try {
            long before = GameClock.now();
            for (int i = 1; i <= 10; i++) {
                monotonic.addAndGet(1_000_000_000L); first.advance(); second.advance();
                assertEquals(before + i * 1_000, GameClock.now());
            }
        } finally { second.destroy(); first.destroy(); }
    }

    @Test void staggeredReplicaPulsesDoNotAddOverlappingTimeAndFailoverExcludesLostLease() throws Exception {
        var first = start();
        var second = start();
        try {
            long before = GameClock.now();
            for (int i = 0; i < 10; i++) {
                monotonic.addAndGet(1_000_000_000L); wall.addAndGet(1_000); first.advance();
                monotonic.addAndGet(100_000_000L); wall.addAndGet(100); second.advance();
            }
            assertEquals(before + 10_900, GameClock.now());
            monotonic.addAndGet(60_000_000_000L); wall.addAndGet(60_000); second.advance();
            assertEquals(before + 10_900, GameClock.now());
            monotonic.addAndGet(1_000_000_000L); wall.addAndGet(1_000); second.advance();
            assertEquals(before + 11_900, GameClock.now());
            first.advance();
            assertEquals(before + 11_900, GameClock.now());
        } finally { second.destroy(); first.destroy(); }
    }
}
