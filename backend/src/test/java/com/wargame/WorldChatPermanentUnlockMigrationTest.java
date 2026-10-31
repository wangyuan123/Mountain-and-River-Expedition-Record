package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldChatPermanentUnlockMigrationTest {
    @Test
    void backfillsPlayersWithThresholdPrestigeOrRetainedPlayerMessages() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, prestige INT)");
            sql.execute("CREATE TABLE chat_messages(id BIGINT PRIMARY KEY, player_id BIGINT NULL)");
            sql.execute("INSERT INTO players(id,prestige) VALUES(1,10000),(2,0),(3,9999),(4,NULL)");
            sql.execute("INSERT INTO chat_messages(id,player_id) VALUES(1,2),(2,NULL)");

            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V149__permanent_world_chat_unlock.sql"));

            try (var rows = sql.executeQuery("SELECT id, world_chat_unlocked FROM players ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong("id"));
                assertTrue(rows.getBoolean("world_chat_unlocked"));
                assertTrue(rows.next());
                assertEquals(2, rows.getLong("id"));
                assertTrue(rows.getBoolean("world_chat_unlocked"));
                assertTrue(rows.next());
                assertEquals(3, rows.getLong("id"));
                assertFalse(rows.getBoolean("world_chat_unlocked"));
                assertTrue(rows.next());
                assertEquals(4, rows.getLong("id"));
                assertFalse(rows.getBoolean("world_chat_unlocked"));
                assertFalse(rows.next());
            }
        }
    }
}
