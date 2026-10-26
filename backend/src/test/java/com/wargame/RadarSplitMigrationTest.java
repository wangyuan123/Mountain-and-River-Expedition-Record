package com.wargame;

import db.migration.V128__split_city_guild_radar;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RadarSplitMigrationTest {
    private Connection connection;
    private Context context;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:radar_split_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        execute("CREATE TABLE players(id BIGINT PRIMARY KEY)");
        execute("INSERT INTO players VALUES(1),(2),(3),(4)");
        execute("""
                CREATE TABLE player_cities (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    owner_id BIGINT,
                    city_slot INT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    UNIQUE(owner_id, city_slot)
                )
                """);
        execute("""
                CREATE TABLE buildings (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    player_id BIGINT NOT NULL REFERENCES players(id),
                    city_slot INT NOT NULL DEFAULT 0,
                    type VARCHAR(50) NOT NULL,
                    level INT DEFAULT 0,
                    slot INT NOT NULL DEFAULT 0,
                    version BIGINT NOT NULL DEFAULT 0,
                    is_slot0 TINYINT GENERATED ALWAYS AS (CASE WHEN slot = 0 THEN 1 ELSE NULL END),
                    UNIQUE(player_id, city_slot, type, is_slot0)
                )
                """);
        execute("""
                CREATE TABLE constructions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    player_id BIGINT NOT NULL REFERENCES players(id),
                    city_slot INT NOT NULL DEFAULT 0,
                    building_type VARCHAR(50) NOT NULL,
                    target_level INT NOT NULL,
                    slot INT,
                    start_at BIGINT DEFAULT 0,
                    finish_at BIGINT DEFAULT 0,
                    version BIGINT NOT NULL DEFAULT 0
                )
                """);
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V127__guild_radar_intelligence.sql"));
        execute("UPDATE radar_monitors SET version=3, revision=17 WHERE player_id=1");
        execute("""
                INSERT INTO radar_contacts
                    (version, observer_id, march_id, guild_id, attacker_id, target_key, threat_kind, status,
                     first_seen_at, last_seen_at, read_at, notice_pending, notice_at, reminder_group,
                     snapshot, sources, expires_at)
                VALUES (8, 1, 21, 3, 2, 'player:11', 'ATTACK', 'ACTIVE',
                        100, 120, 110, TRUE, 105, 'existing-reminder', '{"army":20}', '[11,12]', 300),
                       (9, 1, 22, 3, 2, 'player:12', 'ATTACK', 'DEPARTED',
                        80, 95, 90, FALSE, 85, 'old-reminder', '{"army":10}', '[12]', 250)
                """);
        context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void grantsMainAndSubcityLevelsWithoutChangingSwitchesOrHistory() throws Exception {
        execute("""
                INSERT INTO player_cities(id, owner_id, city_slot, version, radar_enabled, radar_revision)
                VALUES(11,1,0,3,FALSE,7),(12,1,2,8,TRUE,19),(21,2,0,4,FALSE,1)
                """);
        execute("""
                INSERT INTO buildings(player_id, city_slot, type, level, version)
                VALUES(1,0,'radar',3,4),(1,2,'radar',8,6),(2,0,'radar',1,9)
                """);

        migratePreservingHistory();

        assertGuildRadars("1:0:3:0:0", "1:2:8:0:0", "2:0:1:0:0");
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO radar_contacts SELECT id+100, version, observer_id, march_id, guild_id,
                    attacker_id, target_key, threat_kind, status, first_seen_at, last_seen_at, read_at,
                    notice_pending, notice_at, reminder_group, snapshot, sources, expires_at
                FROM radar_contacts WHERE march_id=21
                """));
    }

    @Test
    void skipsMissingZeroNegativeAndNullCompletedRadarLevels() throws Exception {
        execute("INSERT INTO player_cities(owner_id, city_slot) VALUES(1,0),(1,1),(1,2),(1,3),(1,4),(1,5)");
        execute("""
                INSERT INTO buildings(player_id, city_slot, type, level)
                VALUES(1,1,'radar',0),(1,2,'radar',-3),(1,3,'radar',NULL),(1,4,'barracks',10)
                """);
        execute("""
                INSERT INTO constructions(player_id, city_slot, building_type, target_level, finish_at)
                VALUES(1,1,'radar',1,999999),(1,5,'radar',1,999999)
                """);

        migratePreservingHistory();

        assertGuildRadars();
    }

    @Test
    void takesHighestCompletedDuplicateAndClampsOnlyTheGrantedLevel() throws Exception {
        execute("INSERT INTO player_cities(owner_id, city_slot) VALUES(1,0),(1,1),(1,2)");
        execute("""
                INSERT INTO buildings(player_id, city_slot, type, level, slot, version)
                VALUES(1,0,'radar',3,0,4),(1,0,'radar',7,1,5),(1,0,'radar',5,2,6),
                      (1,1,'radar',0,0,7),(1,1,'radar',-5,1,8),(1,1,'radar',11,2,9),
                      (1,1,'radar',999,3,10),(1,2,'radar',2147483647,4,11)
                """);

        migratePreservingHistory();

        assertGuildRadars("1:0:7:0:0", "1:1:10:0:0", "1:2:10:0:0");
    }

    @Test
    void ignoresUnfinishedUpgradeAndDemolitionTargets() throws Exception {
        execute("INSERT INTO player_cities(owner_id, city_slot) VALUES(1,0),(1,1),(1,2),(1,3),(1,4)");
        execute("""
                INSERT INTO buildings(player_id, city_slot, type, level)
                VALUES(1,0,'radar',3),(1,1,'radar',9),(1,2,'radar',0),(1,4,'radar',6)
                """);
        execute("""
                INSERT INTO constructions(player_id, city_slot, building_type, target_level, slot, start_at, finish_at, version)
                VALUES(1,0,'radar',4,0,100,999999,2),(1,1,'radar',10,0,100,999999,3),
                      (1,2,'radar',1,0,100,999999,4),(1,3,'radar',1,0,100,999999,5),
                      (1,4,'radar',5,0,100,999999,6)
                """);

        migratePreservingHistory();

        assertGuildRadars("1:0:3:0:0", "1:1:9:0:0", "1:4:6:0:0");
    }

    @Test
    void preservesExistingGuildRadarsAtAnyLevelOrBuildingSlot() throws Exception {
        execute("INSERT INTO player_cities(owner_id, city_slot) VALUES(1,0),(1,1),(1,2),(1,3)");
        execute("""
                INSERT INTO buildings(player_id, city_slot, type, level, slot, version)
                VALUES(1,0,'radar',9,0,2),(1,1,'radar',7,0,3),(1,2,'radar',5,0,4),(1,3,'radar',5,0,5),
                      (1,0,'guild_radar',2,0,8),(1,1,'guild_radar',0,0,9),
                      (1,2,'guild_radar',4,3,10),(1,3,'guild_radar',12,0,11)
                """);
        var before = snapshot("SELECT * FROM buildings ORDER BY id");

        migratePreservingHistory();
        migratePreservingHistory();

        assertEquals(before, snapshot("SELECT * FROM buildings ORDER BY id"));
        assertGuildRadars("1:0:2:0:8", "1:1:0:0:9", "1:2:4:3:10", "1:3:12:0:11");
    }

    @Test
    void requiresCurrentOwnerAndExactNonNullCitySlot() throws Exception {
        execute("""
                INSERT INTO player_cities(owner_id, city_slot)
                VALUES(1,0),(2,1),(NULL,2),(3,NULL),(1,NULL),(NULL,0)
                """);
        execute("""
                INSERT INTO buildings(player_id, city_slot, type, level)
                VALUES(1,0,'radar',4),(1,1,'radar',5),(1,2,'radar',6),
                      (2,1,'radar',3),(3,0,'radar',7),(4,0,'radar',8),(1,9,'radar',9)
                """);

        migratePreservingHistory();

        assertGuildRadars("1:0:4:0:0", "2:1:3:0:0");
    }

    @Test
    void grantsFullMilitaryCityOneCompatibilityBuildingWithoutEviction() throws Exception {
        execute("INSERT INTO player_cities(owner_id, city_slot) VALUES(1,0)");
        execute("INSERT INTO buildings(player_id, type, level) VALUES(1,'radar',6)");
        try (var insert = connection.prepareStatement("INSERT INTO buildings(player_id, type, level, slot) VALUES(1,'barracks',1,?)")) {
            for (int slot = 0; slot < 34; slot++) {
                insert.setInt(1, slot);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        assertEquals(35, snapshot("SELECT * FROM buildings").size());

        migratePreservingHistory();
        migratePreservingHistory();

        assertEquals(36, snapshot("SELECT * FROM buildings").size());
        assertGuildRadars("1:0:6:0:0");
    }

    @Test
    void repeatMigrationNeverRecalculatesOrRestoresAnExistingGrant() throws Exception {
        execute("INSERT INTO player_cities(owner_id, city_slot) VALUES(1,0)");
        execute("INSERT INTO buildings(player_id, type, level, version) VALUES(1,'radar',2,3)");
        migratePreservingHistory();
        assertGuildRadars("1:0:2:0:0");
        var afterFirstRun = snapshot("SELECT * FROM buildings ORDER BY id");

        migratePreservingHistory();
        assertEquals(afterFirstRun, snapshot("SELECT * FROM buildings ORDER BY id"));
        execute("UPDATE buildings SET level=9, version=4 WHERE type='radar'");
        execute("UPDATE buildings SET level=0, version=5 WHERE type='guild_radar'");
        var afterGameplay = snapshot("SELECT * FROM buildings ORDER BY id");
        migratePreservingHistory();

        assertEquals(afterGameplay, snapshot("SELECT * FROM buildings ORDER BY id"));
        assertGuildRadars("1:0:0:0:5");
    }

    private void migratePreservingHistory() throws Exception {
        var queries = List.of(
                "SELECT * FROM buildings WHERE type <> 'guild_radar' ORDER BY id",
                "SELECT * FROM player_cities ORDER BY id",
                "SELECT * FROM constructions ORDER BY id",
                "SELECT * FROM radar_contacts ORDER BY id",
                "SELECT * FROM radar_monitors ORDER BY player_id");
        var before = new ArrayList<List<List<Object>>>();
        for (var query : queries) before.add(snapshot(query));

        new V128__split_city_guild_radar().migrate(context);

        for (int index = 0; index < queries.size(); index++) {
            assertEquals(before.get(index), snapshot(queries.get(index)), queries.get(index));
        }
    }

    private void assertGuildRadars(String... expected) throws Exception {
        var actual = new ArrayList<String>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT player_id, city_slot, level, slot, version FROM buildings
                WHERE type = 'guild_radar' ORDER BY player_id, city_slot, slot
                """)) {
            while (rows.next()) {
                actual.add(rows.getLong("player_id") + ":" + rows.getInt("city_slot") + ":" + rows.getInt("level")
                        + ":" + rows.getInt("slot") + ":" + rows.getLong("version"));
            }
        }
        assertEquals(List.of(expected), actual);
    }

    private List<List<Object>> snapshot(String query) throws Exception {
        var result = new ArrayList<List<Object>>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                var values = new ArrayList<Object>();
                for (int column = 1; column <= columns; column++) values.add(rows.getObject(column));
                result.add(values);
            }
        }
        return result;
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
