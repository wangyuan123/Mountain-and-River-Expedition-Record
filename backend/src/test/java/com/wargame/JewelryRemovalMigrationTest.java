package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证旧库存定向清理，零库存行也删除，晋升凭证与其他道具不受影响。 */
class JewelryRemovalMigrationTest {
    @Test
    void removesAllRetiredItemsForEveryPlayerAndPreservesActiveInventory() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:jewelryRemoval;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE player_items(player_id BIGINT, item_key VARCHAR(64), count INT)");
            String[] retired = {
                    "gem_pearl", "gem_coral", "gem_glaze", "gem_amber", "gem_agate",
                    "gem_crystal", "gem_jadeite", "gem_jade", "gem_nightpearl",
                    "box_gem", "box_gem_primary", "box_gem_medium",
                    "box_gem_senior", "box_gem_supreme", "box_gem_grand"
            };
            try (var insert = connection.prepareStatement("INSERT INTO player_items VALUES(?, ?, ?)")) {
                for (long playerId : new long[]{1, 2}) {
                    for (String key : retired) {
                        insert.setLong(1, playerId);
                        insert.setString(2, key);
                        insert.setInt(3, playerId == 1 ? 0 : 20);
                        insert.executeUpdate();
                    }
                }
            }
            statement.execute("INSERT INTO player_items VALUES(1,'rank_certificate_2',3),(2,'rank_certificate_17',1),"
                    + "(1,'stage_gift_8',2),(2,'expBook',5),(2,'newbiePack',0)");

            var migration = new ClassPathResource("db/migration/V98__remove_jewelry.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);

            Map<String, Integer> remaining = new LinkedHashMap<>();
            try (var rows = statement.executeQuery("SELECT player_id,item_key,count FROM player_items")) {
                while (rows.next()) {
                    remaining.put(rows.getLong(1) + ":" + rows.getString(2), rows.getInt(3));
                }
            }
            assertEquals(Map.of("1:rank_certificate_2", 3, "2:rank_certificate_17", 1,
                    "1:stage_gift_8", 2, "2:expBook", 5, "2:newbiePack", 0), remaining);
        }
    }
}
