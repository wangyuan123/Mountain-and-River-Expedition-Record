package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class TechnologyReworkMigrationTest {
    @Test void mapsLevelsAndQueuesWithoutDuplicatingPaidResourcesOrChangingClocks() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:techMigration;MODE=MySQL", "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE technologies(id BIGINT AUTO_INCREMENT PRIMARY KEY, player_id BIGINT, type VARCHAR(50), level INT, version BIGINT DEFAULT 0)");
            s.execute("CREATE TABLE tech_research_queue(id BIGINT PRIMARY KEY, tech_type VARCHAR(50), target_level INT, finishes_at BIGINT, cost_steel INT, prerequisite_version VARCHAR(80))");
            s.execute("INSERT INTO technologies(player_id,type,level) VALUES(1,'log_production',4),(1,'inf_load',3),(1,'recon_radar',2),(1,'unit_refit',3),(1,'prod_food',7)");
            s.execute("INSERT INTO tech_research_queue VALUES(1,'log_production',5,12345,500,'old'),(2,'inf_load',4,23456,600,'old'),(3,'unit_refit',3,34567,700,'old'),(4,'recon_radar',2,45678,800,'old')");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V101__technology_rework.sql"));
            for (String spec : new String[]{"prod_food:7", "prod_steel:4", "prod_oil:4", "prod_rare:4", "log_load:6", "recon_radar:3", "unit_refit:5"}) {
                String[] parts = spec.split(":");
                try (var r = s.executeQuery("SELECT level FROM technologies WHERE player_id=1 AND type='" + parts[0] + "'")) {
                    assertTrue(r.next()); assertEquals(Integer.parseInt(parts[1]), r.getInt(1)); assertFalse(r.next());
                }
            }
            try (var r = s.executeQuery("SELECT COUNT(*),SUM(cost_steel),MAX(finishes_at) FROM tech_research_queue")) {
                r.next(); assertEquals(4, r.getInt(1)); assertEquals(2600, r.getInt(2)); assertEquals(45678, r.getLong(3));
            }
            try (var r = s.executeQuery("SELECT tech_type,target_level,prerequisite_version FROM tech_research_queue ORDER BY id")) {
                r.next(); assertEquals("log_production", r.getString(1)); assertEquals(5, r.getInt(2)); assertEquals("old", r.getString(3));
                r.next(); assertEquals("log_load", r.getString(1)); assertEquals(8, r.getInt(2));
                r.next(); assertEquals(5, r.getInt(2)); r.next(); assertEquals(3, r.getInt(2));
            }
        }
    }
    @Test void tenLevelMigrationOnlyChangesLevelsAndPreservesFrozenPaidQueues() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:tenLevelMigration;MODE=MySQL", "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE technologies(id BIGINT AUTO_INCREMENT PRIMARY KEY, player_id BIGINT, type VARCHAR(50), level INT, version BIGINT DEFAULT 0)");
            s.execute("CREATE TABLE tech_research_queue(id BIGINT AUTO_INCREMENT PRIMARY KEY, tech_type VARCHAR(50), target_level INT, started_at BIGINT, finishes_at BIGINT, duration_seconds INT, cost_steel INT, prerequisite_version VARCHAR(80), prerequisite_requirements VARCHAR(100), frozen_at BIGINT)");
            for (String key : new String[]{"unit_refit", "log_warehouse", "recon_level", "recon_radar"}) {
                for (int level = 0; level <= 5; level++) {
                    s.execute("INSERT INTO technologies(player_id,type,level) VALUES(" + level + ",'" + key + "'," + level + ")");
                    s.execute("INSERT INTO tech_research_queue(tech_type,target_level,started_at,finishes_at,duration_seconds,cost_steel,prerequisite_version,prerequisite_requirements,frozen_at) VALUES('" + key + "'," + level + ",1000,987654,321,1234,'old','saved',555)");
                }
            }
            s.execute("INSERT INTO technologies(player_id,type,level) VALUES(99,'attack_tech',9)");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V103__ten_level_research.sql"));
            try (var r = s.executeQuery("SELECT player_id,level FROM technologies WHERE type <> 'attack_tech'")) {
                int count = 0;
                while (r.next()) { assertEquals(r.getInt(1) * 2, r.getInt(2)); count++; }
                assertEquals(24, count);
            }
            try (var r = s.executeQuery("SELECT target_level,started_at,finishes_at,duration_seconds,cost_steel,prerequisite_version,prerequisite_requirements,frozen_at FROM tech_research_queue ORDER BY id")) {
                int count = 0;
                while (r.next()) {
                    assertEquals((count % 6) * 2, r.getInt(1));
                    assertEquals(1000, r.getLong(2)); assertEquals(987654, r.getLong(3));
                    assertEquals(321, r.getInt(4)); assertEquals(1234, r.getInt(5));
                    assertEquals("old", r.getString(6)); assertEquals("saved", r.getString(7)); assertEquals(555, r.getLong(8)); count++;
                }
                assertEquals(24, count);
            }
            try (var r = s.executeQuery("SELECT level FROM technologies WHERE type='attack_tech'")) { assertTrue(r.next()); assertEquals(9, r.getInt(1)); }
        }
    }

}
