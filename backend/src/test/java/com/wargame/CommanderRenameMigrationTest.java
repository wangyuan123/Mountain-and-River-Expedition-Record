package com.wargame;

import com.wargame.model.constants.CommanderRenamePolicy;
import db.migration.V116__commander_rename_card;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommanderRenameMigrationTest {
    @Test
    void backfillsNamesAndIssuesOneProtectedGiftOnlyToInitializedHumanAccounts() throws Exception {
        try (Connection connection = database(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO players VALUES(1,'old','ÉCHO',TRUE,FALSE,'ACTIVE'),"
                    + "(2,'bot',NULL,TRUE,TRUE,'ACTIVE'),(3,'pending',NULL,FALSE,FALSE,'ACTIVE'),"
                    + "(4,'deleted',NULL,TRUE,FALSE,'DELETED'),(5,'disabled',NULL,TRUE,FALSE,'PENDING_DELETION')");
            migrate(connection);
            try (var rows = statement.executeQuery("SELECT * FROM mails ORDER BY to_player_id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong("to_player_id"));
                assertEquals("PROTECTED", rows.getString("attachment_policy"));
                assertEquals("COMMANDER_RENAME_GIFT", rows.getString("attachment_source_type"));
                assertTrue(rows.getString("attach_json").contains("\"qty\":1"));
                assertTrue(rows.getString("attach_json").contains(CommanderRenamePolicy.ITEM_KEY));
                assertFalse(rows.getBoolean("is_claimed"));
                assertTrue(rows.getLong("persisted_real_at") > 0);
                assertTrue(rows.next());
                assertEquals(5, rows.getLong("to_player_id"));
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("SELECT * FROM players ORDER BY id")) {
                while (rows.next()) {
                    long id = rows.getLong("id");
                    assertEquals(id == 1 || id == 5, rows.getBoolean("commander_rename_gift_sent"));
                    assertEquals(0, rows.getLong("commander_renamed_at"));
                    assertEquals(0, rows.getInt("commander_rename_count"));
                    if (id == 1) assertEquals(CommanderRenamePolicy.nameKey("ÉCHO"), rows.getString("display_name_key"));
                    if (id == 2) assertEquals("bot", rows.getString("display_name_key"));
                }
            }
            assertThrows(SQLException.class, () -> statement.execute("UPDATE players SET display_name_key='écho' WHERE id=2"));
            statement.execute("DELETE FROM mails");
            migrate(connection);
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM mails")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void duplicateCanonicalNamesStopBeforeAnyDdlOrGift() throws Exception {
        try (Connection connection = database(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO players VALUES(1,'one','ÉCHO',TRUE,FALSE,'ACTIVE'),"
                    + "(2,'two',' écho ',TRUE,FALSE,'ACTIVE')");
            SQLException failure = assertThrows(SQLException.class, () -> migrate(connection));
            assertTrue(failure.getMessage().contains("1、2"));
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT display_name_key FROM players"));
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM mails")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void failedGiftWriteDoesNotSetPermanentGiftFlagAndCanRetry() throws Exception {
        try (Connection connection = database(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO players VALUES(1,'old',NULL,TRUE,FALSE,'ACTIVE')");
            statement.execute("ALTER TABLE mails ADD CONSTRAINT reject_gift CHECK(to_player_id<>1)");
            assertThrows(SQLException.class, () -> migrate(connection));
            try (var rows = statement.executeQuery("SELECT commander_rename_gift_sent FROM players WHERE id=1")) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1));
            }
            statement.execute("ALTER TABLE mails DROP CONSTRAINT reject_gift");
            migrate(connection);
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM mails")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    private Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:renameMigration" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players(id BIGINT PRIMARY KEY,username VARCHAR(50),display_name VARCHAR(100),"
                    + "game_initialized BOOLEAN,is_bot BOOLEAN,account_status VARCHAR(24))");
            statement.execute("CREATE TABLE mails(id BIGINT AUTO_INCREMENT PRIMARY KEY,from_player_id BIGINT,from_name VARCHAR(50),"
                    + "to_player_id BIGINT,to_name VARCHAR(50),type VARCHAR(16),is_system BOOLEAN,subject VARCHAR(80),body TEXT,"
                    + "attach_json TEXT,is_read BOOLEAN,is_claimed BOOLEAN,created_at BIGINT,persisted_real_at BIGINT NOT NULL,"
                    + "processed_retention_ms BIGINT NOT NULL,unread_retention_ms BIGINT NOT NULL,attachment_policy VARCHAR(20),"
                    + "attachment_source_type VARCHAR(40),attachment_source_id VARCHAR(100))");
        }
        return connection;
    }

    private void migrate(Connection connection) throws Exception {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        new V116__commander_rename_card().migrate(context);
    }
}
