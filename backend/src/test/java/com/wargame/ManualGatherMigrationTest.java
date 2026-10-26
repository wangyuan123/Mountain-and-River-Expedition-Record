package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ManualGatherMigrationTest {
    @Test
    void cancelsPendingAutomationWithoutResettingProgressCargoOrExistingReturns() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:manualGather" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marches(id BIGINT PRIMARY KEY, target_kind VARCHAR(50), action VARCHAR(20), "
                    + "gather_mode VARCHAR(10) DEFAULT 'auto', returning BOOLEAN, gather_after_action VARCHAR(10), "
                    + "gather_after_action_result VARCHAR(12), gather_after_action_reason VARCHAR(255), "
                    + "gather_start_at BIGINT, gather_end_at BIGINT, gather_amount INT, route_data VARCHAR(100))");
            statement.execute("CREATE TABLE wild_tiles(id BIGINT PRIMARY KEY, gather_mode VARCHAR(10), "
                    + "gathering BOOLEAN, gather_start_at BIGINT, gather_end_at BIGINT, gather_load INT, gather_harvested INT)");
            statement.execute("INSERT INTO marches VALUES"
                    + "(1,'wild_gather','gather','auto',FALSE,'abandon','pending',NULL,100,200,500,'[[1,1],[2,2]]'),"
                    + "(2,'wild_gather','gather','auto',TRUE,'abandon','abandoned',NULL,100,200,500,'[[2,2],[1,1]]'),"
                    + "(3,'wild_gather','gather','auto',NULL,'abandon','pending',NULL,100,200,500,'[[1,1],[2,2]]'),"
                    + "(4,'player','conquer','auto',FALSE,'retain',NULL,NULL,0,0,0,NULL)");
            statement.execute("INSERT INTO wild_tiles VALUES(1,'auto',TRUE,100,200,500,NULL),(2,'auto',FALSE,100,200,500,250)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V119__manual_gather_only.sql"));
            // 重跑不得改写已取消的结果或历史返程货物。
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V119__manual_gather_only.sql"));
            try (var rows = statement.executeQuery("SELECT * FROM marches WHERE target_kind = 'wild_gather' ORDER BY id")) {
                while (rows.next()) {
                    int id = rows.getInt("id");
                    assertEquals("manual", rows.getString("gather_mode"));
                    assertEquals(id == 2 ? "abandon" : "retain", rows.getString("gather_after_action"));
                    assertEquals(id == 2 ? "abandoned" : "cancelled", rows.getString("gather_after_action_result"));
                    assertEquals(100, rows.getLong("gather_start_at"));
                    assertEquals(200, rows.getLong("gather_end_at"));
                    assertEquals(500, rows.getInt("gather_amount"));
                    assertNotNull(rows.getString("route_data"));
                    assertEquals(id == 2, rows.getBoolean("returning"));
                }
            }
            try (var rows = statement.executeQuery("SELECT * FROM wild_tiles ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("manual", rows.getString("gather_mode"));
                assertTrue(rows.getBoolean("gathering"));
                assertEquals(100, rows.getLong("gather_start_at"));
                assertEquals(200, rows.getLong("gather_end_at"));
                assertEquals(500, rows.getInt("gather_load"));
                assertTrue(rows.next());
                assertEquals("manual", rows.getString("gather_mode"));
                assertEquals(250, rows.getInt("gather_harvested"));
            }
            statement.execute("INSERT INTO marches(id) VALUES(5)");
            try (var rows = statement.executeQuery("SELECT gather_mode FROM marches WHERE id = 5")) {
                assertTrue(rows.next());
                assertEquals("manual", rows.getString(1));
            }
        }
    }
}
