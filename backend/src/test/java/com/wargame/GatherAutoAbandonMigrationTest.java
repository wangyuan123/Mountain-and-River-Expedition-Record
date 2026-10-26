package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GatherAutoAbandonMigrationTest {
    @Test
    void legacyTasksRetainAndOnlyBindToOwnershipPresentAtUpgrade() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:gatherMigration" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE wild_tiles(id BIGINT PRIMARY KEY, occupied BOOLEAN, occupied_by BIGINT, occupied_city_slot INT)");
            statement.execute("CREATE TABLE marches(id BIGINT PRIMARY KEY, target_kind VARCHAR(50), target_id VARCHAR(100), player_id BIGINT, returning BOOLEAN)");
            statement.execute("INSERT INTO wild_tiles VALUES(1,TRUE,10,2),(2,TRUE,20,0),(3,FALSE,NULL,0)");
            statement.execute("INSERT INTO marches VALUES(1,'wild_gather','1',10,TRUE),(2,'wild_gather','1',10,FALSE),"
                    + "(3,'wild_gather','2',10,FALSE),(4,'wild_gather','3',10,FALSE),(5,'wild_gather','99',10,FALSE),"
                    + "(6,'wild_gather','1',10,NULL),(7,'wild','1',10,FALSE)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V117__gather_auto_abandon.sql"));
            try (var rows = statement.executeQuery("SELECT * FROM marches ORDER BY id")) {
                while (rows.next()) {
                    int id = rows.getInt("id");
                    assertEquals("retain", rows.getString("gather_after_action"));
                    assertNull(rows.getString("gather_after_action_result"));
                    assertEquals(id == 1, rows.getBoolean("gather_resource_settled"));
                    if (id == 2 || id == 6) {
                        assertEquals(0, rows.getLong("gather_occupation_version"));
                        assertEquals(2, rows.getInt("gather_owner_city_slot"));
                    } else if (id >= 3 && id <= 5) {
                        assertEquals(-1, rows.getLong("gather_occupation_version"));
                        assertNull(rows.getObject("gather_owner_city_slot"));
                    } else {
                        assertNull(rows.getObject("gather_occupation_version"));
                    }
                }
            }
        }
    }
}
