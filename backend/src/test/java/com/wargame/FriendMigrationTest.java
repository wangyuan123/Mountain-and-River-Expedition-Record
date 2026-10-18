package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class FriendMigrationTest {
    @Test void migrationPreservesOldRowsAndCreatesPrivateContactsWithSafeDefaults() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:friendMigration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players(id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO players VALUES(1),(2),(3)");
            statement.execute("CREATE TABLE private_chat_conversations(id BIGINT PRIMARY KEY, owner_id BIGINT, peer_id BIGINT, deleted_through BIGINT)");
            statement.execute("INSERT INTO private_chat_conversations VALUES(1,1,2,15)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V100__friends_and_chat_clear.sql"));
            try (var row = statement.executeQuery("SELECT friend_request_policy,block_stranger_messages FROM players WHERE id=1")) {
                assertTrue(row.next()); assertEquals("APPROVAL", row.getString(1)); assertFalse(row.getBoolean(2));
            }
            try (var row = statement.executeQuery("SELECT cleared_through,deleted_through FROM private_chat_conversations WHERE id=1")) {
                assertTrue(row.next()); assertEquals(0, row.getLong(1)); assertEquals(15, row.getLong(2));
            }
            statement.execute("INSERT INTO friend_groups(id,owner_id,name) VALUES(10,1,'战友')");
            statement.execute("INSERT INTO friend_contacts(owner_id,peer_id,group_id,is_friend) VALUES(1,2,10,TRUE)");
            statement.execute("INSERT INTO friend_requests(sender_id,recipient_id,created_at) VALUES(3,1,1)");
            statement.execute("DELETE FROM friend_groups WHERE id=10");
            try (var row = statement.executeQuery("SELECT group_id,blocked FROM friend_contacts")) {
                assertTrue(row.next()); assertNull(row.getObject(1)); assertFalse(row.getBoolean(2));
            }
            assertThrows(java.sql.SQLException.class, () -> statement.execute("INSERT INTO friend_contacts(owner_id,peer_id) VALUES(1,2)"));
            statement.execute("DELETE FROM players WHERE id=1");
            try (var row = statement.executeQuery("SELECT COUNT(*) FROM friend_contacts")) { assertTrue(row.next()); assertEquals(0, row.getLong(1)); }
            try (var row = statement.executeQuery("SELECT COUNT(*) FROM friend_requests")) { assertTrue(row.next()); assertEquals(0, row.getLong(1)); }
        }
    }
}
