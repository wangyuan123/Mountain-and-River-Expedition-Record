package com.wargame;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

/** Only run against an explicitly supplied, disposable database; never use the game database. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "WARGAME_TEST_MYSQL_URL", matches = ".+")
class MySqlMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getenv("WARGAME_TEST_MYSQL_URL");
        String user = System.getenv().getOrDefault("WARGAME_TEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("WARGAME_TEST_MYSQL_PASSWORD", "");
        // Build the old schema and seed a balance before Spring applies the new migrations.
        Flyway previous = Flyway.configure().dataSource(url, user, password).target("26").load();
        if (previous.info().current() == null) {
            previous.migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO players(username, password_hash) VALUES ('migration-sentinel', 'test-only')");
                statement.executeUpdate("INSERT INTO resources(player_id, gold, diamond) "
                        + "SELECT id, 12345, 678 FROM players WHERE username='migration-sentinel'");
            }
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
    }

    @Test
    void upgradesExistingDataAndValidatesTheRealMysqlSchema() {
        var resource = jdbc.queryForMap("SELECT r.gold, r.diamond, r.version, r.city_slot FROM resources r "
                + "JOIN players p ON p.id=r.player_id WHERE p.username='migration-sentinel'");
        assertEquals(12345, ((Number) resource.get("gold")).intValue());
        assertEquals(678, ((Number) resource.get("diamond")).intValue());
        assertEquals(0L, ((Number) resource.get("version")).longValue());
        assertEquals(0, ((Number) resource.get("city_slot")).intValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='32' AND success=1", Integer.class));
        Long playerId = jdbc.queryForObject("SELECT id FROM players WHERE username='migration-sentinel'", Long.class);
        try {
            jdbc.update("INSERT INTO army_units(player_id,city_slot,type,count) VALUES(?,0,'truck',5)", playerId);
            jdbc.update("INSERT INTO army_units(player_id,city_slot,type,count) VALUES(?,1,'truck',7)", playerId);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM army_units WHERE player_id=? AND type='truck'", Integer.class, playerId));
            assertThrows(org.springframework.dao.DuplicateKeyException.class,
                    () -> jdbc.update("INSERT INTO army_units(player_id,city_slot,type,count) VALUES(?,1,'truck',9)", playerId));
        } finally { jdbc.update("DELETE FROM army_units WHERE player_id=? AND type='truck'", playerId); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='28' AND success=1", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND index_name='idx_wild_world_coordinates'", Integer.class) > 0);
    }
}
