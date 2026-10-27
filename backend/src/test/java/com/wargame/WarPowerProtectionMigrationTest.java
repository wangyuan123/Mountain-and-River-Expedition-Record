package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class WarPowerProtectionMigrationTest {
    @Test void migrationCreatesIndexesAndCascadesSnapshots() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:war_power_migration;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("CREATE TABLE players(id BIGINT PRIMARY KEY)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V134__war_power_protection.sql"));
            sql.execute("INSERT INTO players VALUES(1)");
            sql.execute("INSERT INTO player_power_snapshots(player_id, observed_at, formation_power, overall_power) VALUES(1, 100, 10, 20)");
            sql.execute("DELETE FROM players WHERE id=1");
            var rows = sql.executeQuery("SELECT count(*) FROM player_power_snapshots");
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }
}
