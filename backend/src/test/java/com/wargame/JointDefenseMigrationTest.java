package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JointDefenseMigrationTest {
    @Test void oldBattleStaysLegacyAndGarrisonFoodBeginsAtPublishedGameTime() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:joint" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE battle_sessions(id BIGINT PRIMARY KEY, battle_log LONGTEXT)");
            sql.execute("CREATE TABLE player_cities(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE marches(id BIGINT PRIMARY KEY, stationed BOOLEAN, target_kind VARCHAR(20), target_id VARCHAR(40), army LONGTEXT)");
            sql.execute("CREATE TABLE game_clock(singleton_id INT PRIMARY KEY, game_time BIGINT)");
            sql.execute("INSERT INTO game_clock VALUES(1, 123456789)");
            sql.execute("INSERT INTO battle_sessions VALUES(1, '历史规则战报')");
            sql.execute("INSERT INTO player_cities VALUES(2),(3)");
            sql.execute("INSERT INTO marches VALUES(7, TRUE, 'player', '2', '{\"infantry\":100}'),(8, FALSE, 'player', '3', '{\"infantry\":50}')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V126__joint_garrison_defense.sql"));
            try (var rows = sql.executeQuery("SELECT * FROM player_cities ORDER BY id")) {
                assertTrue(rows.next()); assertFalse(rows.getBoolean("accept_garrison"));
                assertEquals(123456789, rows.getLong("garrison_food_at")); assertEquals(0, rows.getDouble("garrison_food_remainder"));
                assertTrue(rows.next()); assertNull(rows.getObject("garrison_food_at")); assertFalse(rows.getBoolean("accept_garrison"));
            }
            try (var rows = sql.executeQuery("SELECT * FROM marches ORDER BY id")) {
                assertTrue(rows.next()); assertEquals(123456789, rows.getLong("garrison_food_at"));
                assertEquals("{\"infantry\":100}", rows.getString("army")); assertFalse(rows.getBoolean("displaced"));
                assertTrue(rows.next()); assertNull(rows.getObject("garrison_food_at"));
            }
            try (var rows = sql.executeQuery("SELECT * FROM battle_sessions")) {
                assertTrue(rows.next()); assertNull(rows.getObject("joint_defense")); assertEquals("历史规则战报", rows.getString("battle_log"));
            }
        }
    }
}
