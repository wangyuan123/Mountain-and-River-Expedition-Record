package com.wargame;

import db.migration.V153__continuous_population_fraction;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 旧存档逃亡余量转为连续人口时保留真实人数；正常增长余量不变。 */
class ContinuousPopulationMigrationTest {
    @Test void preservesMainAndBranchPopulationWhenRemainderChangesMeaning() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE players(id BIGINT,civilian_population INT,population_growth_remainder DOUBLE,morale INT)");
            sql.execute("CREATE TABLE player_cities(id BIGINT,owner_id BIGINT,city_slot INT,civilian_population INT,population_growth_remainder DOUBLE,morale INT)");
            sql.execute("CREATE TABLE buildings(player_id BIGINT,city_slot INT,type VARCHAR(20),level INT)");
            sql.execute("INSERT INTO buildings VALUES(1,0,'house',1),(2,0,'house',1),(1,1,'house',2)");
            sql.execute("INSERT INTO players VALUES(1,1500,0.3,70),(2,1100,0.3,70),(3,50,0.6,70)");
            sql.execute("INSERT INTO player_cities VALUES(10,1,0,1500,0.3,70),(11,1,1,2500,0.2,70)");
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            new V153__continuous_population_fraction().migrate(context);
            try (var rows = sql.executeQuery("SELECT civilian_population,population_growth_remainder FROM players ORDER BY id")) {
                assertTrue(rows.next()); assertEquals(1499, rows.getInt(1)); assertEquals(.7, rows.getDouble(2), 1e-9);
                assertTrue(rows.next()); assertEquals(1100, rows.getInt(1)); assertEquals(.3, rows.getDouble(2), 1e-9);
                assertTrue(rows.next()); assertEquals(49, rows.getInt(1)); assertEquals(.4, rows.getDouble(2), 1e-9);
            }
            try (var rows = sql.executeQuery("SELECT civilian_population,population_growth_remainder FROM player_cities ORDER BY id")) {
                assertTrue(rows.next()); assertEquals(1499, rows.getInt(1)); assertEquals(.7, rows.getDouble(2), 1e-9);
                assertTrue(rows.next()); assertEquals(2499, rows.getInt(1)); assertEquals(.8, rows.getDouble(2), 1e-9);
            }
        }
    }
}
