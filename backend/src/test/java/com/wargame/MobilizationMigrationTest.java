package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class MobilizationMigrationTest {
    @Test void existingAccountsKeepDefaultDefenseAndReceiptsEnforceIdempotency() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:mobilization_migration;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, sortie_army TEXT)");
            sql.execute("INSERT INTO players VALUES(1, '{\"infantry\":100}')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V138__account_mobilization.sql"));
            var rows = sql.executeQuery("SELECT * FROM players WHERE id=1");
            assertTrue(rows.next());
            assertEquals("{\"infantry\":100}", rows.getString("sortie_army"));
            assertNull(rows.getString("mobilization_sortie_army"));
            assertEquals(0L, rows.getLong("mobilization_cycle"));
            sql.execute("INSERT INTO mobilization_uses VALUES('1:request-1', 1, 'mobilizationOrder', 1, 100)");
            assertThrows(java.sql.SQLException.class, () -> sql.execute(
                    "INSERT INTO mobilization_uses VALUES('1:request-1', 1, 'mobilizationOrder', 1, 100)"));
        }
    }
}
