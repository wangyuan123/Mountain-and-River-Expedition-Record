package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class NewbieProtectionMigrationTest {
    @Test
    void historicalAccountsStayUnprotectedAndNewAccountsCanAwaitFirstEntry() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:newbieMigration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, prestige INT)");
            statement.execute("INSERT INTO players VALUES(1,1234)");
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V102__newbie_protection.sql"));
            statement.execute("INSERT INTO players(id,prestige,newbie_protection_started_at) VALUES(2,0,NULL)");
            try (var rows = statement.executeQuery("SELECT * FROM players ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1234, rows.getInt("prestige"));
                assertEquals(0L, rows.getLong("newbie_protection_started_at"));
                assertFalse(rows.wasNull());
                assertFalse(rows.getBoolean("newbie_protection_ended"));
                assertTrue(rows.next());
                assertNull(rows.getObject("newbie_protection_started_at"));
                assertFalse(rows.getBoolean("newbie_protection_ended"));
            }
        }
    }
}
