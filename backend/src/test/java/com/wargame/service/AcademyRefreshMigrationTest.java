package com.wargame.service;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class AcademyRefreshMigrationTest {
    @Test
    void existingPlayersReceiveFreshQuotaWithoutLosingTheirCandidates() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:academyQuotaMigration;MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE academy (player_id BIGINT, refresh_at BIGINT, officers TEXT)");
            statement.execute("INSERT INTO players VALUES (1)");
            statement.execute("INSERT INTO academy VALUES (1, 9999999999999, 'existing-candidates')");
            try (var stream = getClass().getResourceAsStream("/db/migration/V53__academy_refresh_quotas.sql")) {
                assertNotNull(stream);
                RunScript.execute(connection, new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
            try (var row = statement.executeQuery("SELECT * FROM players WHERE id = 1")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt("academy_refresh_round_count"));
                assertEquals(0, row.getInt("academy_refresh_daily_count"));
                assertEquals(0L, row.getLong("academy_refresh_at"));
                assertNull(row.getDate("academy_refresh_day"));
            }
            try (var row = statement.executeQuery("SELECT * FROM academy WHERE player_id = 1")) {
                assertTrue(row.next());
                assertEquals(0L, row.getLong("refresh_at"));
                assertEquals("existing-candidates", row.getString("officers"));
            }
        }
    }
}
