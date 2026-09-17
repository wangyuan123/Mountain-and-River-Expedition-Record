package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class ComplianceMigrationTest {
    @Test void addsTablesWithoutResettingExistingPlayersOrAllowingInvalidLimits() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:compliance_migration;MODE=MySQL", "sa", ""); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, username VARCHAR(50))");
            statement.execute("INSERT INTO players VALUES (1, 'existing-player')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V36__identity_and_play_access.sql"));
            try (var result = statement.executeQuery("SELECT game_initialized,username FROM players WHERE id=1")) {
                assertTrue(result.next()); assertTrue(result.getBoolean(1)); assertEquals("existing-player", result.getString(2));
            }
            assertThrows(java.sql.SQLException.class, () -> statement.execute("INSERT INTO identity_subjects(id,birth_cipher,verified_until,updated_at,daily_limit_seconds) VALUES ('a','x',1,1,3601)"));
            statement.execute("INSERT INTO identity_subjects(id,birth_cipher,verified_until,updated_at) VALUES ('a','encrypted',1,1)");
            statement.execute("INSERT INTO player_identities(player_id,subject_id,verified_at) VALUES (1,'a',1)");
            assertThrows(java.sql.SQLException.class, () -> statement.execute("INSERT INTO player_identities(player_id,subject_id,verified_at) VALUES (2,'a',1)"));
        }
    }
}
