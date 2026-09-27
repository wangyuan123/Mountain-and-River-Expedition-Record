package com.wargame;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class SortieMigrationTest {
    @Test
    void existingPlayersKeepTheirRankAndTacticsAndDefaultToAutomaticSortie() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:sortieMigration;MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players (id BIGINT PRIMARY KEY, military_rank INT, outgoing_battle_actions TEXT)");
            statement.execute("INSERT INTO players VALUES (1, 17, '{\"infantry\":\"HOLD\"}')");
            try (var stream = getClass().getResourceAsStream("/db/migration/V55__sortie_army_preferences.sql")) {
                assertNotNull(stream);
                RunScript.execute(connection, new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
            try (var row = statement.executeQuery("SELECT * FROM players WHERE id=1")) {
                assertTrue(row.next());
                assertEquals(17, row.getInt("military_rank"));
                assertEquals("{\"infantry\":\"HOLD\"}", row.getString("outgoing_battle_actions"));
                assertNull(row.getString("sortie_army"));
            }
        }
    }
}
