package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/** 迁移不向运行中的旧会话补墙，数据库保留六位小数连续耐久。 */
class ConquestWallMigrationTest {
    @Test void oldRowsRemainWallFreeAndNewRowsRetainFractionalDamage() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:wallMigration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE battle_sessions(id BIGINT PRIMARY KEY, round_no INT, battle_log LONGTEXT)");
            statement.execute("INSERT INTO battle_sessions VALUES(1,3,'旧战斗')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V96__conquest_wall_breach.sql"));
            try (var row = statement.executeQuery("SELECT * FROM battle_sessions WHERE id=1")) {
                assertTrue(row.next()); assertNull(row.getObject("wall_level_snapshot"));
                assertNull(row.getBigDecimal("wall_hp_remaining")); assertNull(row.getString("battle_rules_version"));
                assertEquals(3, row.getInt("round_no")); assertEquals("旧战斗", row.getString("battle_log"));
            }
            statement.execute("UPDATE battle_sessions SET wall_level_snapshot=10,wall_max_hp=2500000,wall_hp_remaining=1987272.727273,wall_defense=200,battle_phase='ATTACK_WALL' WHERE id=1");
            try (var row = statement.executeQuery("SELECT wall_hp_remaining FROM battle_sessions WHERE id=1")) {
                assertTrue(row.next()); assertEquals(new BigDecimal("1987272.727273"), row.getBigDecimal(1));
            }
        }
    }
}
