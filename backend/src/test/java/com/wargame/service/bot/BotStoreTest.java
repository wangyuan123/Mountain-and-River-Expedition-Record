package com.wargame.service.bot;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BotStoreTest {
    private SingleConnectionDataSource source;
    private BotStore store;
    private JdbcTemplate jdbc;
    private final BotStore.Settings settings = new BotStore.Settings(true, false, 0, 45, 35, 2, 500);

    @BeforeEach
    void setUp() throws Exception {
        var connection = DriverManager.getConnection("jdbc:h2:mem:bot_" + System.nanoTime() + ";MODE=MySQL", "sa", "");
        source = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, username VARCHAR(100), display_name VARCHAR(100))");
        jdbc.update("INSERT INTO players VALUES (1,'bot','bot'),(2,'human','human')");
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V130__bot_players.sql"));
        store = new BotStore(jdbc);
    }

    @AfterEach
    void close() { source.destroy(); }

    @Test
    void onlyExplicitlyEnabledAccountsAreDue() {
        assertTrue(store.due(1000, 10).isEmpty());
        store.configure(1, settings, 1000);
        assertEquals(java.util.List.of(1L), store.due(1000, 10));
        assertTrue(store.due(999, 10).isEmpty());
        store.configure(1, new BotStore.Settings(false, false, 0, 45, 35, 2, 500), 1000);
        assertTrue(store.due(1000, 10).isEmpty());
        assertEquals(1, store.list().size());
    }

    @Test
    void recordsFailureBackoffAndTargetCooldownThenResetsOnSuccess() {
        store.configure(1, settings, 1000);
        BotAction action = BotAction.target("ATTACK", "bandit", 7, Map.of("infantry", 20), "test");
        store.complete(store.find(1, true).orElseThrow(), action, false, "blocked", "{}", 1000);
        var failed = store.find(1, false).orElseThrow();
        assertEquals(46000, failed.nextActionAt());
        assertEquals("bandit:7", failed.lastTarget());
        assertEquals(301000, failed.targetCooldownUntil());
        store.complete(failed, action, false, "blocked", "{}", 46000);
        assertEquals(136000, store.find(1, false).orElseThrow().nextActionAt());
        store.complete(store.find(1, false).orElseThrow(), action, true, "ok", "{}", 136000);
        assertEquals(0, store.find(1, false).orElseThrow().failureCount());
        assertEquals(3, store.logs(1, 30).size());
    }

    @Test
    void retainsBoundedLogsAndCascadesDeletion() {
        store.configure(1, settings, 0);
        for (int index = 0; index < 205; index++) {
            store.complete(store.find(1, false).orElseThrow(), BotAction.simple("WAIT", "", "waiting"), true, "waiting", "{}", index);
        }
        assertEquals(200, store.logs(1, 1000).size());
        assertEquals(205, store.find(1, false).orElseThrow().actionCount());
        jdbc.update("DELETE FROM players WHERE id=1");
        assertTrue(store.list().isEmpty());
        assertTrue(store.logs(1, 30).isEmpty());
    }
}
