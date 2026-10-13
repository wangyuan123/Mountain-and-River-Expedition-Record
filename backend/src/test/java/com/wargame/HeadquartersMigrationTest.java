package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/** 迁移增加可空快照，进行中的旧战斗必须原样保留。 */
class HeadquartersMigrationTest {
    @Test void preservesExistingBattlesAndAllowsIndependentSnapshots() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:headquarters_migration;MODE=MySQL", "sa", "")) {
            var statement = connection.createStatement();
            statement.execute("CREATE TABLE battle_sessions(id BIGINT PRIMARY KEY, battle_log LONGTEXT)");
            statement.execute("INSERT INTO battle_sessions VALUES(1,'旧战斗')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V83__headquarters_command_bonus.sql"));
            try (var result = statement.executeQuery("SELECT * FROM battle_sessions WHERE id=1")) {
                assertTrue(result.next());
                assertEquals("旧战斗", result.getString("battle_log"));
                assertNull(result.getString("attacker_command_bonus"));
                assertNull(result.getString("defender_command_bonus"));
            }
            statement.execute("UPDATE battle_sessions SET attacker_command_bonus='{\"military\":237}', defender_command_bonus='{\"military\":100}' WHERE id=1");
            try (var result = statement.executeQuery("SELECT * FROM battle_sessions WHERE id=1")) {
                assertTrue(result.next());
                assertEquals("{\"military\":237}", result.getString("attacker_command_bonus"));
                assertEquals("{\"military\":100}", result.getString("defender_command_bonus"));
            }
        }
    }
}
