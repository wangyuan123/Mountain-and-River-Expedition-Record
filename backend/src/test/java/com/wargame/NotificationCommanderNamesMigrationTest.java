package com.wargame;

import db.migration.V122__notification_commander_names;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationCommanderNamesMigrationTest {
    @Test
    void repairsGeneratedNotificationsWithoutRewritingPlayerContent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, username VARCHAR(50), display_name VARCHAR(100))");
            statement.execute("CREATE TABLE mails(id BIGINT PRIMARY KEY, from_player_id BIGINT, from_name VARCHAR(100), to_player_id BIGINT, to_name VARCHAR(100), is_system BOOLEAN, type VARCHAR(16), subject VARCHAR(200), body TEXT)");
            statement.execute("CREATE TABLE chat_messages(id BIGINT PRIMARY KEY, player_id BIGINT, username VARCHAR(100), content TEXT)");
            statement.execute("INSERT INTO players VALUES(1,'login_one','烈火$统帅'),(2,'login_two','山林统帅'),(3,'secret_login',NULL)");
            statement.execute("INSERT INTO mails VALUES(1,NULL,'系统',2,'login_two',TRUE,'combat','【战争警报】玩家「login_one」向你宣战！','警报！玩家「login_one」已向你的城市「玩家「login_one」」发起宣战！'),"
                    + "(2,1,'login_one',2,'login_two',FALSE,'player','玩家「login_one」','玩家「login_two」'),"
                    + "(3,NULL,'系统',3,'secret_login',TRUE,'reward','奖励','正文'),"
                    + "(4,NULL,'系统',1,'login_one',TRUE,'combat','【宣战公告】已对玩家「login_two」宣战','玩家「login_two」的城市'),"
                    + "(5,NULL,'军团系统',2,'login_two',TRUE,'alliance','新的入团申请','login_one 申请加入军团「先锋」。')");
            statement.execute("INSERT INTO chat_messages VALUES(1,NULL,'系统','⚔ login_one 对玩家「login_two」宣战!'),"
                    + "(2,1,'login_one','⚔ login_one 对玩家「login_two」宣战!'),"
                    + "(3,NULL,'系统','玩家「login_one」打开了「黄金箱」，获得：黄金×10。')");
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            var migration = new V122__notification_commander_names();
            migration.migrate(context);
            migration.migrate(context);

            try (var rows = statement.executeQuery("SELECT * FROM mails ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("山林统帅", rows.getString("to_name"));
                assertEquals("【战争警报】玩家「烈火$统帅」向你宣战！", rows.getString("subject"));
                assertEquals("警报！玩家「烈火$统帅」已向你的城市「玩家「login_one」」发起宣战！", rows.getString("body"));
                assertTrue(rows.next());
                assertEquals("烈火$统帅", rows.getString("from_name"));
                assertEquals("玩家「login_one」", rows.getString("subject"));
                assertEquals("玩家「login_two」", rows.getString("body"));
                assertTrue(rows.next());
                assertEquals("统帅3", rows.getString("to_name"));
                assertTrue(rows.next());
                assertEquals("【宣战公告】已对玩家「山林统帅」宣战", rows.getString("subject"));
                assertEquals("玩家「山林统帅」的城市", rows.getString("body"));
                assertTrue(rows.next());
                assertEquals("烈火$统帅 申请加入军团「先锋」。", rows.getString("body"));
            }
            try (var rows = statement.executeQuery("SELECT * FROM chat_messages ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("⚔ 烈火$统帅 对玩家「山林统帅」宣战!", rows.getString("content"));
                assertTrue(rows.next());
                assertEquals("⚔ login_one 对玩家「login_two」宣战!", rows.getString("content"));
                assertTrue(rows.next());
                assertEquals("玩家「烈火$统帅」打开了「黄金箱」，获得：黄金×10。", rows.getString("content"));
            }
        }
    }
}
