package com.wargame;

import com.wargame.model.constants.CommanderRenamePolicy;
import db.migration.V148__legacy_commander_names;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyCommanderNamesMigrationTest {
    @Test
    void backfillsLegacyNamesAndRepairsOnlyGeneratedItemPlayerFieldsAcrossPages() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, username VARCHAR(50), display_name VARCHAR(100),"
                    + "display_name_key VARCHAR(200) UNIQUE, commander_renamed_at BIGINT DEFAULT 0, commander_rename_count INT DEFAULT 0)");
            statement.execute("CREATE TABLE chat_messages(id BIGINT PRIMARY KEY, player_id BIGINT, username VARCHAR(100), content TEXT)");
            statement.execute("INSERT INTO players(id,username,display_name,display_name_key) VALUES"
                    + "(2,'深入山林开荒种地',NULL,'深入山林开荒种地'),(3,'BlankOwner','  ','blankowner'),"
                    + "(4,'private_login','烈火$统帅','烈火$统帅'),(5,'other','统帅3','统帅3')");
            statement.execute("UPDATE players SET commander_renamed_at=123,commander_rename_count=2 WHERE id=4");
            for (int id = 1; id <= 201; id++) {
                statement.execute("INSERT INTO chat_messages VALUES(" + id
                        + ",NULL,'系统','玩家「统帅2」打开了「资源大礼包」，获得：粮食×5000000、统帅2×1。')");
            }
            statement.execute("INSERT INTO chat_messages VALUES"
                    + "(202,2,'深入山林开荒种地','玩家「统帅2」打开了「黄金箱」'),"
                    + "(203,NULL,'系统','公告提到玩家「统帅2」打开了「黄金箱」'),"
                    + "(204,NULL,'系统','玩家「统帅3」打开了「黄金箱」'),"
                    + "(205,NULL,'系统','玩家「烈火$统帅」打开了「黄金箱」')");
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            var migration = new V148__legacy_commander_names();
            migration.migrate(context);
            migration.migrate(context);

            try (var rows = statement.executeQuery("SELECT * FROM players ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("深入山林开荒种地", rows.getString("display_name"));
                assertEquals(0, rows.getLong("commander_renamed_at"));
                assertEquals(0, rows.getInt("commander_rename_count"));
                assertTrue(rows.next());
                assertEquals("BlankOwner", rows.getString("display_name"));
                assertEquals(CommanderRenamePolicy.nameKey("BlankOwner"), rows.getString("display_name_key"));
                assertTrue(rows.next());
                assertEquals("烈火$统帅", rows.getString("display_name"));
                assertEquals(123, rows.getLong("commander_renamed_at"));
                assertEquals(2, rows.getInt("commander_rename_count"));
            }
            try (var rows = statement.executeQuery("SELECT * FROM chat_messages ORDER BY id")) {
                for (int id = 1; id <= 201; id++) {
                    assertTrue(rows.next());
                    assertEquals("玩家「深入山林开荒种地」打开了「资源大礼包」，获得：粮食×5000000、统帅2×1。", rows.getString("content"));
                    assertNull(rows.getObject("player_id"));
                }
                assertTrue(rows.next());
                assertEquals("玩家「统帅2」打开了「黄金箱」", rows.getString("content"));
                assertTrue(rows.next());
                assertEquals("公告提到玩家「统帅2」打开了「黄金箱」", rows.getString("content"));
                assertTrue(rows.next());
                assertEquals("玩家「统帅3」打开了「黄金箱」", rows.getString("content"));
                assertTrue(rows.next());
                assertEquals("玩家「烈火$统帅」打开了「黄金箱」", rows.getString("content"));
                assertFalse(rows.next());
            }
        }
    }
}
