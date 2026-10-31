package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProductionRewardSalaryMigrationTest {
    @Test
    void historicalOrdersHaveNoPendingRewardAndCitiesStartWithoutInventedArrears() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE army_production_queue(id BIGINT PRIMARY KEY, unit_count INT)");
            sql.execute("INSERT INTO army_production_queue VALUES(1,100),(2,50)");
            sql.execute("CREATE TABLE resources(player_id BIGINT,city_slot INT,gold BIGINT)");
            sql.execute("INSERT INTO resources VALUES(1,0,100),(1,2,0)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V146__production_rewards_and_salary_arrears.sql"));
            try (var orders = sql.executeQuery("SELECT * FROM army_production_queue ORDER BY id")) {
                assertTrue(orders.next());
                assertEquals(100, orders.getInt("unit_count"));
                assertEquals(0, orders.getInt("prestige_reward"));
                assertTrue(orders.next());
                assertEquals(50, orders.getInt("unit_count"));
                assertEquals(0, orders.getInt("prestige_reward"));
            }
            try (var cities = sql.executeQuery("SELECT * FROM resources ORDER BY city_slot")) {
                assertTrue(cities.next());
                assertEquals(100, cities.getLong("gold"));
                assertNull(cities.getObject("salary_arrears_started_at"));
                assertEquals(0, cities.getLong("salary_arrears_penalties"));
                assertTrue(cities.next());
                assertEquals(0, cities.getLong("gold"));
                assertNull(cities.getObject("salary_arrears_started_at"));
                assertEquals(0, cities.getLong("salary_arrears_penalties"));
            }
        }
    }
}
