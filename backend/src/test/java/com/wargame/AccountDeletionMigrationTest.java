package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class AccountDeletionMigrationTest {
    @Test void migrationPreservesActivePlayersAndSchedulesLegacyDeletions() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:account-migration;MODE=MySQL", "sa", "");
             var sql = connection.createStatement()) {
            sql.execute("create table players(id bigint primary key, disabled int, disabled_at bigint)");
            sql.execute("insert into players values (1, 0, 0), (2, 1, 1000), (3, null, null)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V35__account_deletion_lifecycle.sql"));
            try (var row = sql.executeQuery("select * from players where id=2")) {
                assertTrue(row.next());
                assertEquals("PENDING_DELETION", row.getString("account_status"));
                assertEquals(604801000L, row.getLong("recover_until"));
                assertEquals(1, row.getLong("auth_version"));
            }
            try (var rows = sql.executeQuery("select * from players where id<>2")) {
                while (rows.next()) {
                    assertEquals("ACTIVE", rows.getString("account_status"));
                    assertEquals(0, rows.getLong("auth_version"));
                }
            }
        }
    }
}
