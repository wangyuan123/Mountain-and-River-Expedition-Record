package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class InterceptionAmbushMigrationTest {
    @Test
    void legacyMarchesRemainUnpreparedAndAmbushAllowancePersists() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:interception_migration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marches(id BIGINT PRIMARY KEY, army VARCHAR(100))");
            statement.execute("CREATE TABLE incoming_marches(id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO marches VALUES(1,'{infantry:10}')");
            statement.execute("INSERT INTO incoming_marches VALUES(1)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V111__interception_ambush.sql"));
            try (var rows = statement.executeQuery("SELECT army,intercept_deploy_at,intercept_ready_at,ambush_used FROM marches")) {
                assertTrue(rows.next()); assertEquals("{infantry:10}", rows.getString(1));
                assertNull(rows.getObject(2)); assertNull(rows.getObject(3)); assertFalse(rows.getBoolean(4));
            }
            statement.execute("UPDATE marches SET intercept_deploy_at=10000,intercept_ready_at=15000,ambush_used=TRUE WHERE id=1");
            try (var rows = statement.executeQuery("SELECT intercept_ready_at,ambush_used FROM marches WHERE id=1")) {
                assertTrue(rows.next()); assertEquals(15_000, rows.getLong(1)); assertTrue(rows.getBoolean(2));
            }
            try (var rows = statement.executeQuery("SELECT ambush_used FROM incoming_marches")) {
                assertTrue(rows.next()); assertFalse(rows.getBoolean(1));
            }
        }
    }
}
