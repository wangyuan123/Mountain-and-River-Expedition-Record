package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class UnitUnlockMigrationTest {
    @Test void addsSnapshotsAndPreservesQuestClaimsWhileClosingOnlyCompletedThirdChapter() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:unitUnlockMigration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE army_production_queue(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE unit_equipment_orders(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE player_quest_stages(player_id BIGINT, stage_id VARCHAR(32), status VARCHAR(32), completed_at BIGINT)");
            statement.execute("CREATE TABLE player_quests(player_id BIGINT, quest_id VARCHAR(32), status VARCHAR(32))");
            statement.execute("INSERT INTO player_quest_stages VALUES(1,'stage3','in_progress',NULL),(2,'stage3','in_progress',NULL),(3,'stage3','claimed',1234),(1,'stage5','claimed',5678)");
            for (String id : new String[]{"q3_1", "q3_3", "q3_4", "q3_5", "q3_6", "q3_7", "q3_8"}) {
                statement.execute("INSERT INTO player_quests VALUES(1,'" + id + "','completed')");
            }
            for (String id : new String[]{"q3_1", "q3_3", "q3_4", "q3_5", "q3_6", "q3_7"}) {
                statement.execute("INSERT INTO player_quests VALUES(2,'" + id + "','completed')");
            }
            statement.execute("INSERT INTO player_quests VALUES(1,'q3_2','claimed')");

            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V105__unit_unlock_and_naval_quest.sql"));

            try (var rows = statement.executeQuery("SELECT status,completed_at FROM player_quest_stages WHERE player_id=1 AND stage_id='stage3'")) {
                assertTrue(rows.next()); assertEquals("completed", rows.getString(1)); assertTrue(rows.getLong(2) > 0);
            }
            try (var rows = statement.executeQuery("SELECT status FROM player_quest_stages WHERE player_id=2 AND stage_id='stage3'")) {
                assertTrue(rows.next()); assertEquals("in_progress", rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT status,completed_at FROM player_quest_stages WHERE player_id=3 AND stage_id='stage3'")) {
                assertTrue(rows.next()); assertEquals("claimed", rows.getString(1)); assertEquals(1234, rows.getLong(2));
            }
            try (var rows = statement.executeQuery("SELECT status,completed_at FROM player_quest_stages WHERE player_id=1 AND stage_id='stage5'")) {
                assertTrue(rows.next()); assertEquals("claimed", rows.getString(1)); assertEquals(5678, rows.getLong(2));
            }
            try (var rows = statement.executeQuery("SELECT status FROM player_quests WHERE player_id=1 AND quest_id='q3_2'")) {
                assertTrue(rows.next()); assertEquals("claimed", rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT unlock_rule_version,unlock_requirements FROM army_production_queue")) {
                assertFalse(rows.next());
            }
        }
    }
}
