package com.wargame;

import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.TechDef;
import com.wargame.service.DepotService;
import com.wargame.service.TechService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class CounterReconRemovalTest {
    @Test
    void retiredActionsAreRejectedBeforeTouchingInventoryOrResources() {
        assertFalse(TechDef.TECHS.containsKey("recon_stealth"));
        assertFalse(ItemDef.ITEMS.containsKey("cloak"));
        // Null dependencies make any accidental resource/inventory access fail the test.
        var tech = new TechService(null, null, null, null);
        var depot = new DepotService(null, null, null, null, null, null, null);
        assertEquals(false, tech.upgrade(1L, "recon_stealth").get("success"));
        assertEquals(false, depot.useItem(1L, "cloak", null, null).get("success"));
    }

    @Test
    void migrationRemovesRetiredDataAndPreservesOtherResearchAndBuffs() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:retired_recon;MODE=MySQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE technologies (type VARCHAR(50), level INT)");
            sql.execute("INSERT INTO technologies VALUES ('recon_stealth', 5), ('recon_level', 5)");
            sql.execute("CREATE TABLE player_items (item_key VARCHAR(50), count INT)");
            sql.execute("INSERT INTO player_items VALUES ('cloak', 3), ('shield', 2)");
            sql.execute("CREATE TABLE city_state (cloak_until BIGINT, shield_until BIGINT, march_boost_until BIGINT)");
            sql.execute("INSERT INTO city_state VALUES (9999999999999, 123456, 654321)");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V29__remove_counter_reconnaissance.sql"));

            try (var rows = sql.executeQuery("SELECT * FROM technologies")) {
                assertTrue(rows.next());
                assertEquals("recon_level", rows.getString("type"));
                assertEquals(5, rows.getInt("level"));
                assertFalse(rows.next());
            }
            try (var rows = sql.executeQuery("SELECT * FROM player_items")) {
                assertTrue(rows.next());
                assertEquals("shield", rows.getString("item_key"));
                assertEquals(2, rows.getInt("count"));
                assertFalse(rows.next());
            }
            try (var rows = sql.executeQuery("SELECT * FROM city_state")) {
                assertEquals(2, rows.getMetaData().getColumnCount());
                assertTrue(rows.next());
                assertEquals(123456, rows.getLong("shield_until"));
                assertEquals(654321, rows.getLong("march_boost_until"));
            }
        }
    }
}
