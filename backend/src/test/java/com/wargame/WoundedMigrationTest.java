package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class WoundedMigrationTest {
    @Test
    void schemaIncludesVersionAndAllowsIndependentBatchesOfSameUnitAndCity() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:wounded_migration;MODE=MySQL", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V31__create_wounded_units.sql"));
            try (var statement = connection.createStatement()) {
                String insert = "INSERT INTO wounded_units(player_id,city_id,city_x,city_y,city_name,type,count,created_at,expires_at,recovery_percent) "
                        + "VALUES(1,2,10,10,'主城','scout',50,1000,604801000,50)";
                statement.executeUpdate(insert);
                statement.executeUpdate(insert);
                try (var result = statement.executeQuery("SELECT COUNT(*), SUM(version) FROM wounded_units")) {
                    assertTrue(result.next());
                    assertEquals(2, result.getInt(1));
                    assertEquals(0, result.getLong(2));
                }
                assertEquals(2, statement.executeUpdate("DELETE FROM wounded_units WHERE expires_at <= 604801000"));
            }
        }
    }
}
