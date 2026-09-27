package com.wargame.service;

import com.wargame.model.constants.AvatarDef;
import com.wargame.model.entity.Player;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AvatarMigrationTest {
    @Test
    void legacyAvatarsAreAssignedOnceWithoutReplacingChosenAvatars() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:historicalAvatarMigration;MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players (id BIGINT PRIMARY KEY, avatar VARCHAR(255), account_status VARCHAR(24) DEFAULT 'ACTIVE', version BIGINT DEFAULT 0)");
            try (var insert = connection.prepareStatement("INSERT INTO players (id, avatar) VALUES (?, ?)")) {
                String[] oldAvatars = { null, "", "  ", "https://example.com/old.png",
                        "img/avatars/commander-1.svg", "img/avatars/commander-8.svg",
                        "img/avatars/historical/missing.webp" };
                for (int index = 0; index < oldAvatars.length; index++) {
                    insert.setInt(1, index + 1);
                    insert.setString(2, oldAvatars[index]);
                    insert.executeUpdate();
                }
                for (int index = 0; index < AvatarDef.PRESETS.size(); index++) {
                    insert.setInt(1, index + 100);
                    insert.setString(2, AvatarDef.PRESETS.get(index));
                    insert.executeUpdate();
                }
            }
            statement.execute("INSERT INTO players (id, avatar, account_status) VALUES (200, '', 'DELETED')");
            migrate(connection);
            Map<Long, String> assigned = new LinkedHashMap<>();
            try (var rows = statement.executeQuery("SELECT * FROM players ORDER BY id")) {
                while (rows.next()) {
                    long id = rows.getLong("id");
                    String avatar = rows.getString("avatar");
                    assigned.put(id, avatar);
                    if (id == 200) {
                        assertEquals("", avatar);
                        assertEquals(0, rows.getLong("version"));
                    } else if (id >= 100) {
                        assertEquals(AvatarDef.PRESETS.get((int) id - 100), avatar);
                        assertEquals(0, rows.getLong("version"));
                    } else {
                        assertTrue(AvatarDef.isPreset(avatar));
                        assertEquals(1, rows.getLong("version"));
                    }
                }
            }
            assertEquals(16, assigned.size());
            migrate(connection);
            try (var rows = statement.executeQuery("SELECT * FROM players ORDER BY id")) {
                while (rows.next()) {
                    long id = rows.getLong("id");
                    assertEquals(assigned.get(id), rows.getString("avatar"));
                    assertEquals(id < 100 ? 1 : 0, rows.getLong("version"));
                }
            }
        }
    }

    @Test
    void newlyCreatedPlayersReceiveAnAvailableAvatarThatStaysStable() {
        Player player = new Player();
        String avatar = player.getAvatar();
        assertTrue(AvatarDef.isPreset(avatar));
        assertEquals(avatar, player.getAvatar());
    }

    private void migrate(Connection connection) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/V54__historical_player_avatars.sql")) {
            assertNotNull(stream);
            RunScript.execute(connection, new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
}
