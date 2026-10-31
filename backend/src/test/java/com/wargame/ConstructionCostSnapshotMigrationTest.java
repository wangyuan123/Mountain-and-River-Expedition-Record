package com.wargame;

import com.wargame.model.constants.GameData;
import com.wargame.util.JsonUtil;
import db.migration.V147__construction_cost_snapshots;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 历史施工按迁移时旧口径冻结，后续科技变化不能再次改变退款基数。 */
class ConstructionCostSnapshotMigrationTest {
    @Test
    void freezesLegacyCostsWithoutChangingOrdersOrResearch() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE technologies(player_id BIGINT,type VARCHAR(50),level INT)");
            sql.execute("INSERT INTO technologies VALUES(1,'log_build',2),(1,'log_food',10)");
            sql.execute("CREATE TABLE constructions(id BIGINT PRIMARY KEY,player_id BIGINT,city_slot INT,"
                    + "building_type VARCHAR(50),target_level INT,start_at BIGINT,finish_at BIGINT)");
            sql.execute("INSERT INTO constructions VALUES(1,1,0,'house',2,100,200),"
                    + "(2,1,2,'house',3,101,201),(3,2,0,'house',1,102,202),"
                    + "(4,2,0,'retired_building',1,103,203)");
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            new V147__construction_cost_snapshots().migrate(context);
            sql.execute("UPDATE technologies SET level=10 WHERE type='log_build'");
            try (var rows = sql.executeQuery("SELECT * FROM constructions ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(cost(2, 2), JsonUtil.parseIntMap(rows.getString("cost_snapshot")));
                assertEquals(100, rows.getLong("start_at")); assertEquals(200, rows.getLong("finish_at"));
                assertTrue(rows.next());
                assertEquals(2, rows.getInt("city_slot"));
                assertEquals(cost(3, 2), JsonUtil.parseIntMap(rows.getString("cost_snapshot")));
                assertTrue(rows.next());
                assertEquals(cost(1, 0), JsonUtil.parseIntMap(rows.getString("cost_snapshot")));
                assertTrue(rows.next());
                assertNull(rows.getString("cost_snapshot"));
                assertEquals("retired_building", rows.getString("building_type"));
                assertFalse(rows.next());
            }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM technologies")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
        }
    }

    private Map<String, Integer> cost(int level, int technology) {
        var house = GameData.BUILDINGS.get("house");
        double multiplier = Math.pow(house.growth(), level - 1) * (1 - 0.05 * technology);
        Map<String, Integer> expected = new LinkedHashMap<>();
        house.baseCost().forEach((key, amount) -> expected.put(key, (int) Math.floor(amount * multiplier)));
        return expected;
    }
}
