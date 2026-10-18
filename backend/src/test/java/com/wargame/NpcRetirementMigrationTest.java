package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class NpcRetirementMigrationTest {
    @Test
    void removesIdleCitiesRetainsReferencesAndRenamesAllEightLandNames() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:npc_retirement;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("CREATE TABLE npc_cities(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE marches(target_kind VARCHAR(30), target_id VARCHAR(50))");
            sql.execute("CREATE TABLE battle_sessions(target_kind VARCHAR(30), target_id VARCHAR(50))");
            sql.execute("CREATE TABLE bandits(id BIGINT PRIMARY KEY, name VARCHAR(100), level INT, army VARCHAR(100), defeated BOOLEAN)");
            sql.execute("INSERT INTO npc_cities VALUES (101),(102),(103),(104)");
            sql.execute("INSERT INTO marches VALUES ('npc','102'),('bandit','104')");
            sql.execute("INSERT INTO battle_sessions VALUES ('npc','103')");
            String[] names = {"日寇前哨", "日寇营地", "日寇炮楼", "日寇据点", "日寇补给站", "雇佣兵营", "武装走私队", "雇佣兵基地"};
            try (var insert = connection.prepareStatement("INSERT INTO bandits VALUES (?, ?, ?, ?, ?)")) {
                for (int i = 0; i < names.length; i++) {
                    insert.setInt(1, i + 1); insert.setString(2, names[i] + " Lv." + (i + 1));
                    insert.setInt(3, i + 1); insert.setString(4, "{\"infantry\":5}"); insert.setBoolean(5, i % 2 == 0);
                    insert.executeUpdate();
                }
            }
            sql.execute("INSERT INTO bandits VALUES (9,'日寇海域守军 Lv.30',30,'{\"destroyer\":2}',TRUE)");
            String script = new String(new ClassPathResource("db/migration/V92__remove_npc_cities_unify_land_bandits.sql")
                    .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("CONVERT(npc_cities.id USING utf8mb4) COLLATE utf8mb4_unicode_ci", "CAST(npc_cities.id AS VARCHAR(50))");
            for (String statement : script.split(";")) {
                if (!statement.strip().isBlank()) {
                    sql.execute(statement.strip());
                }
            }
            var cities = sql.executeQuery("SELECT id FROM npc_cities ORDER BY id");
            assertTrue(cities.next()); assertEquals(102, cities.getLong(1));
            assertTrue(cities.next()); assertEquals(103, cities.getLong(1));
            assertFalse(cities.next());
            var bandits = sql.executeQuery("SELECT * FROM bandits ORDER BY id");
            for (int i = 0; i < names.length; i++) {
                assertTrue(bandits.next()); assertEquals("寇 Lv." + (i + 1), bandits.getString("name"));
                assertEquals(i + 1, bandits.getInt("level"));
                assertEquals("{\"infantry\":5}", bandits.getString("army"));
                assertEquals(i % 2 == 0, bandits.getBoolean("defeated"));
            }
            assertTrue(bandits.next()); assertEquals("日寇海域守军 Lv.30", bandits.getString("name"));
            assertEquals("{\"destroyer\":2}", bandits.getString("army")); assertTrue(bandits.getBoolean("defeated"));
            assertFalse(bandits.next());
        }
    }
}
