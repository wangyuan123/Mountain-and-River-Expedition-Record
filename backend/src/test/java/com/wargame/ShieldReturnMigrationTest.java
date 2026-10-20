package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class ShieldReturnMigrationTest {
    @Test
    void migrationPreservesExistingMarchesWithoutPublishingTheirReturnRoutes() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:shield_return_migration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marches(id BIGINT PRIMARY KEY, returning BOOLEAN, start_at BIGINT, arrive_at BIGINT)");
            statement.execute("INSERT INTO marches VALUES(1,TRUE,1000,2000)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V108__shield_return_visibility.sql"));
            try (var rows = statement.executeQuery("SELECT * FROM marches")) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean("returning"));
                assertEquals(1000, rows.getLong("start_at"));
                assertEquals(2000, rows.getLong("arrive_at"));
                assertNull(rows.getString("return_reason"), "历史返程无法判定原因，不能自动公开给守方");
            }
            statement.executeUpdate("UPDATE marches SET return_reason='shield' WHERE id=1");
            try (var rows = statement.executeQuery("SELECT return_reason FROM marches WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals("shield", rows.getString(1));
            }
        }
    }
}
