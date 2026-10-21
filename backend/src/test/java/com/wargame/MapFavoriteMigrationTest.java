package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class MapFavoriteMigrationTest {
    @Test
    void addsAccountFavoritesWithoutChangingExistingPlayers() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:map_favorite_migration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, username VARCHAR(100))");
            statement.execute("INSERT INTO players VALUES(1,'commander')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V109__map_coordinate_favorites.sql"));
            try (var rows = statement.executeQuery("SELECT username,map_favorites FROM players")) {
                assertTrue(rows.next()); assertEquals("commander", rows.getString(1)); assertNull(rows.getString(2));
            }
        }
    }
}
