package db.migration;

import com.wargame.model.constants.GameData;
import com.wargame.util.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.*;

/** 旧施工缺少实扣历史，按迁移时的旧退款口径冻结；不声称恢复不可追溯的开工价格。 */
public class V147__construction_cost_snapshots extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE constructions ADD COLUMN cost_snapshot TEXT NULL");
        }
        Map<Long, Integer> levels = new HashMap<>();
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(
                "SELECT player_id, MAX(level) AS level FROM technologies WHERE type='log_build' GROUP BY player_id")) {
            while (rows.next()) levels.put(rows.getLong("player_id"), Math.max(0, Math.min(10, rows.getInt("level"))));
        }
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(
                "SELECT id,player_id,building_type,target_level FROM constructions");
             var update = connection.prepareStatement("UPDATE constructions SET cost_snapshot=? WHERE id=?")) {
            while (rows.next()) {
                var building = GameData.BUILDINGS.get(rows.getString("building_type"));
                if (building == null) continue;
                double multiplier = Math.pow(building.growth(), Math.max(0, rows.getInt("target_level") - 1))
                        * (1 - .05 * levels.getOrDefault(rows.getLong("player_id"), 0));
                Map<String, Integer> cost = new LinkedHashMap<>();
                building.baseCost().forEach((key, amount) -> cost.put(key, (int) Math.floor(amount * multiplier)));
                update.setString(1, JsonUtil.toJson(cost)); update.setLong(2, rows.getLong("id")); update.addBatch();
            }
            update.executeBatch();
        }
    }
}
