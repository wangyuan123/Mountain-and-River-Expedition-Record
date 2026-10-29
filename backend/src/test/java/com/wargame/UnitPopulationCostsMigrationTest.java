package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class UnitPopulationCostsMigrationTest {
    @Test void migrationSnapshotsLegacyCostsAndLeavesCivilianBalancesAndSchedulesAlone() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:population_migration;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("CREATE TABLE army_production_queue(id BIGINT PRIMARY KEY, unit_type VARCHAR(50), unit_count INT, finishes_at BIGINT)");
            sql.execute("INSERT INTO army_production_queue VALUES(1,'carrier',2,12345),(2,'special',3,54321),(3,'rocket',4,99999)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V140__unit_population_costs.sql"));
            var rows = sql.executeQuery("SELECT cost_population, finishes_at FROM army_production_queue ORDER BY id");
            assertTrue(rows.next()); assertEquals(16, rows.getInt(1)); assertEquals(12345, rows.getLong(2));
            assertTrue(rows.next()); assertEquals(6, rows.getInt(1)); assertEquals(54321, rows.getLong(2));
            assertTrue(rows.next()); assertEquals(12, rows.getInt(1)); assertEquals(99999, rows.getLong(2));
            sql.execute("INSERT INTO army_population_balances(player_id,unit_type,reference_count,refundable_population) VALUES(1,'carrier',1,8)");
            assertThrows(java.sql.SQLException.class, () -> sql.execute(
                    "INSERT INTO army_population_balances(player_id,unit_type) VALUES(1,'carrier')"));
        }
    }
}
