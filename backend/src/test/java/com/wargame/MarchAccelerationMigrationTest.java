package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class MarchAccelerationMigrationTest {
    @Test void migrationPreservesExistingClocksAndDefaultsToUnusedAcceleration() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:march_acceleration_migration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marches(id BIGINT PRIMARY KEY, start_at BIGINT, arrive_at BIGINT, returning BOOLEAN)");
            statement.execute("INSERT INTO marches VALUES(1,100000,400000,FALSE),(2,200000,500000,TRUE)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V86__march_acceleration.sql"));
            try (var result = statement.executeQuery("SELECT * FROM marches ORDER BY id")) {
                for (int id = 1; id <= 2; id++) {
                    assertTrue(result.next());
                    assertEquals(id * 100_000L, result.getLong("start_at"));
                    assertEquals((id + 3) * 100_000L, result.getLong("arrive_at"));
                    assertEquals(id == 2, result.getBoolean("returning"));
                    assertNull(result.getObject("base_duration_ms"));
                    assertFalse(result.getBoolean("outbound_accelerated"));
                    assertFalse(result.getBoolean("return_accelerated"));
                }
            }
        }
    }

    @Test void allPendingMigrationsHaveUniqueVersions() throws Exception {
        var versions = new HashSet<String>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
            String version = resource.getFilename().split("__", 2)[0];
            assertTrue(versions.add(version), "重复的迁移版本：" + resource.getFilename());
        }
    }
}
