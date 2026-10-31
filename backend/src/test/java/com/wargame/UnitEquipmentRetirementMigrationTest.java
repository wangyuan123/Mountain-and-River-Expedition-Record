package com.wargame;

import db.migration.V144__retire_unit_equipment;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnitEquipmentRetirementMigrationTest {
    @Test
    void refundsFrozenCostsOnceAndPreservesOfficersActiveResearchAndMarchLoad() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:retire_equipment;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("CREATE TABLE resources(player_id BIGINT, city_slot INT, food BIGINT, steel BIGINT, oil BIGINT, rare BIGINT, gold BIGINT, version BIGINT DEFAULT 0)");
            sql.execute("INSERT INTO resources VALUES(1,0,100,100,100,100,100,0),(1,2,100,100,100,100,100,0)");
            sql.execute("CREATE TABLE unit_equipment_orders(player_id BIGINT, city_slot INT, cost TEXT)");
            sql.execute("INSERT INTO unit_equipment_orders VALUES(1,2,'{\"steel\":300,\"oil\":120,\"rare\":30}'),(1,9,'{\"steel\":70}')");
            sql.execute("CREATE TABLE unit_loadouts(player_id BIGINT, slots TEXT)");
            sql.execute("INSERT INTO unit_loadouts VALUES(1,'{\"protection\":\"ue_infantry_helmet\"}')");
            sql.execute("CREATE TABLE technologies(player_id BIGINT, type VARCHAR(50), level INT)");
            sql.execute("INSERT INTO technologies VALUES(1,'unit_refit',10),(1,'log_load',4)");
            sql.execute("CREATE TABLE tech_research_queue(player_id BIGINT, city_slot INT, tech_type VARCHAR(50), cost_food INT, cost_steel INT, cost_oil INT, cost_rare INT, cost_gold INT)");
            sql.execute("INSERT INTO tech_research_queue VALUES(1,2,'unit_refit',80,90,20,10,6000),(1,0,'attack_tech',100,200,0,0,6000)");
            sql.execute("CREATE TABLE player_items(item_key VARCHAR(80), count INT)");
            sql.execute("INSERT INTO player_items VALUES('ue_infantry_helmet',2),('recruit_military_weapon',3),('universalSkillBook',1)");
            sql.execute("CREATE TABLE player_guide(step_id VARCHAR(80))");
            sql.execute("INSERT INTO player_guide VALUES('ob3_task_equipment'),('ob3_task_truck')");
            sql.execute("CREATE TABLE marches(id BIGINT, unit_equipment_snapshot TEXT)");
            sql.execute("INSERT INTO marches VALUES(1,'{\"@log:load\":4,\"@ue:truck:load\":25}')");
            sql.execute("CREATE TABLE battle_sessions(id BIGINT, defender_equipment_sources TEXT)");
            sql.execute("INSERT INTO battle_sessions VALUES(1,'{\"2\":{\"army\":{\"infantry~s1\":10}}}')");

            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            connection.setAutoCommit(false);
            var migration = new V144__retire_unit_equipment();
            migration.migrate(context); connection.commit();
            migration.migrate(context); connection.commit();

            var city = sql.executeQuery("SELECT * FROM resources WHERE city_slot=2");
            assertTrue(city.next());
            assertEquals(180, city.getLong("food")); assertEquals(490, city.getLong("steel"));
            assertEquals(240, city.getLong("oil")); assertEquals(140, city.getLong("rare")); assertEquals(6100, city.getLong("gold"));
            var home = sql.executeQuery("SELECT steel FROM resources WHERE city_slot=0");
            assertTrue(home.next()); assertEquals(170, home.getLong(1));
            var active = sql.executeQuery("SELECT type FROM technologies");
            assertTrue(active.next()); assertEquals("log_load", active.getString(1)); assertFalse(active.next());
            var research = sql.executeQuery("SELECT tech_type FROM tech_research_queue");
            assertTrue(research.next()); assertEquals("attack_tech", research.getString(1)); assertFalse(research.next());
            var items = sql.executeQuery("SELECT SUM(count) FROM player_items");
            assertTrue(items.next()); assertEquals(4, items.getInt(1));

            connection.setAutoCommit(true);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V145__drop_unit_equipment_storage.sql"));
            assertThrows(java.sql.SQLException.class, () -> sql.executeQuery("SELECT * FROM unit_loadouts"));
            assertThrows(java.sql.SQLException.class, () -> sql.executeQuery("SELECT * FROM unit_equipment_orders"));
            var march = sql.executeQuery("SELECT action_snapshot FROM marches");
            assertTrue(march.next());
            var snapshot = com.wargame.util.JsonUtil.parseIntMap(march.getString(1));
            assertEquals(1.2, com.wargame.model.constants.TechnologyRules.loadMultiplier(snapshot));
            assertEquals(600, mock(com.wargame.service.MarchService.class, CALLS_REAL_METHODS).calcArmyLoad(java.util.Map.of("truck",1), snapshot));
            var support = sql.executeQuery("SELECT defender_support_sources FROM battle_sessions");
            assertTrue(support.next()); assertTrue(support.getString(1).contains("infantry~s1"));
        }
    }
}
