package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class WildOccupationMigrationTest {
    @Test void migrationPreservesOwnerGarrisonAndGatherCityButAssignsLegacyTerritoryToMainCity() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:wild_city_migration;MODE=MySQL", "sa", "")) {
            var statement = connection.createStatement();
            statement.execute("CREATE TABLE wild_tiles(id BIGINT PRIMARY KEY, occupied_by BIGINT, occupied BOOLEAN, "
                    + "garrison TEXT, gather_city_slot INT)");
            statement.execute("INSERT INTO wild_tiles VALUES (1,42,TRUE,'{\"infantry\":7}',2), (2,NULL,FALSE,'{}',0)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V93__wild_occupation_city.sql"));
            var row = statement.executeQuery("SELECT * FROM wild_tiles WHERE id=1");
            assertTrue(row.next()); assertEquals(42L, row.getLong("occupied_by"));
            assertEquals(0, row.getInt("occupied_city_slot")); assertEquals(2, row.getInt("gather_city_slot"));
            assertEquals("{\"infantry\":7}", row.getString("garrison")); assertTrue(row.getBoolean("occupied"));
            statement.execute("UPDATE wild_tiles SET occupied_city_slot=1 WHERE id=1");
            row = statement.executeQuery("SELECT occupied_city_slot FROM wild_tiles WHERE id=1");
            assertTrue(row.next()); assertEquals(1, row.getInt(1));
        }
    }
}
