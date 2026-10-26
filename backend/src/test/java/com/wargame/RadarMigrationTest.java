package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RadarMigrationTest {
    @Test void migrationKeepsEveryCityClosedAndCreatesPersistentDeduplication() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:radar" + UUID.randomUUID() + ";MODE=MySQL", "sa", ""); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE players(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE player_cities(id BIGINT PRIMARY KEY, owner_id BIGINT)");
            sql.execute("INSERT INTO players VALUES(1),(2)"); sql.execute("INSERT INTO player_cities VALUES(3,1),(4,2)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V127__guild_radar_intelligence.sql"));
            try (var rows = sql.executeQuery("SELECT radar_enabled,radar_revision FROM player_cities")) {
                while (rows.next()) { assertFalse(rows.getBoolean(1)); assertEquals(0, rows.getLong(2)); }
            }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM radar_monitors")) { assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM radar_contacts")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        }
    }
}
