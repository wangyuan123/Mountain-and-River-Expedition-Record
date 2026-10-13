package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class ScoutProductionMigrationTest {
    @Test
    void legacyScoutOrdersKeepFactoryWithoutChangingPaidSchedules() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:scout_production_migration;MODE=MySQL", "sa", "")) {
            var statement = connection.createStatement();
            statement.execute("CREATE TABLE army_production_queue(id BIGINT PRIMARY KEY, unit_type VARCHAR(50), building_type VARCHAR(50), finishes_at BIGINT)");
            statement.execute("INSERT INTO army_production_queue VALUES (1,'scout',NULL,1234), (2,'scout','airport',2345), (3,'infantry',NULL,3456)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V91__scout_production_airport.sql"));
            var rows = statement.executeQuery("SELECT * FROM army_production_queue ORDER BY id");
            assertTrue(rows.next()); assertEquals("factory", rows.getString("building_type")); assertEquals(1234, rows.getLong("finishes_at"));
            assertTrue(rows.next()); assertEquals("airport", rows.getString("building_type")); assertEquals(2345, rows.getLong("finishes_at"));
            assertTrue(rows.next()); assertNull(rows.getString("building_type")); assertEquals(3456, rows.getLong("finishes_at"));
            assertFalse(rows.next());
        }
    }
}
