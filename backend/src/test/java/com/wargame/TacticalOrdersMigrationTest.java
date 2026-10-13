package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class TacticalOrdersMigrationTest {
    @Test
    void preservesExistingSessionAndStoresBothSidesSeparately() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:tactical_orders_migration;MODE=MySQL", "sa", "")) {
            var statement = connection.createStatement();
            statement.execute("CREATE TABLE battle_sessions(id BIGINT PRIMARY KEY, round_no INT, battle_log LONGTEXT)");
            statement.execute("INSERT INTO battle_sessions VALUES(1,3,'旧战斗')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V88__battle_persistent_orders.sql"));
            try (var result = statement.executeQuery("SELECT * FROM battle_sessions WHERE id=1")) {
                assertTrue(result.next());
                assertEquals(3, result.getInt("round_no"));
                assertEquals("旧战斗", result.getString("battle_log"));
                assertNull(result.getString("attacker_orders"));
                assertNull(result.getString("defender_orders"));
            }
            String attacker = "{\"infantry\":{\"action\":\"RETREAT\",\"focusTarget\":null}}";
            String defender = "{\"infantry\":{\"action\":\"HOLD\",\"focusTarget\":null}}";
            try (var update = connection.prepareStatement("UPDATE battle_sessions SET attacker_orders=?, defender_orders=? WHERE id=1")) {
                update.setString(1, attacker);
                update.setString(2, defender);
                update.executeUpdate();
            }
            try (var result = statement.executeQuery("SELECT * FROM battle_sessions WHERE id=1")) {
                assertTrue(result.next());
                assertEquals(attacker, result.getString("attacker_orders"));
                assertEquals(defender, result.getString("defender_orders"));
            }
        }
    }
}
