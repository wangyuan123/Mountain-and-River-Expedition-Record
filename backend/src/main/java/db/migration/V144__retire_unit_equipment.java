package db.migration;

import com.wargame.util.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** 取消兵种装备：按订单冻结成本全额退款，清除旧物品、配装及专属科技。 */
public class V144__retire_unit_equipment extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT player_id, city_slot, cost FROM unit_equipment_orders")) {
            while (rows.next()) {
                refund(connection, rows.getLong("player_id"), rows.getInt("city_slot"),
                        JsonUtil.parseIntMap(rows.getString("cost")));
            }
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT player_id, city_slot, cost_food, cost_steel, cost_oil, cost_rare, cost_gold FROM tech_research_queue WHERE tech_type='unit_refit'")) {
            while (rows.next()) {
                refund(connection, rows.getLong("player_id"), rows.getInt("city_slot"), Map.of(
                        "food", rows.getInt("cost_food"), "steel", rows.getInt("cost_steel"),
                        "oil", rows.getInt("cost_oil"), "rare", rows.getInt("cost_rare"), "gold", rows.getInt("cost_gold")));
            }
        }
        // 退款与清理共用 Flyway 事务；后续单独执行 DDL，避免 MySQL 隐式提交造成重复退款。
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM unit_equipment_orders");
            statement.executeUpdate("DELETE FROM unit_loadouts");
            statement.executeUpdate("DELETE FROM tech_research_queue WHERE tech_type='unit_refit'");
            statement.executeUpdate("DELETE FROM technologies WHERE type='unit_refit'");
            statement.executeUpdate("DELETE FROM player_items WHERE LEFT(item_key,3)='ue_'");
            statement.executeUpdate("DELETE FROM player_guide WHERE step_id IN ('ob3_task_equipment','ob3_reward_task_equipment')");
        }
    }

    /** 原城市已消失时退回主城；账号资源也已删除的历史订单无需重建账号。 */
    private void refund(Connection connection, long player, int city, Map<String, Integer> cost) throws SQLException {
        String sql = "UPDATE resources SET food=COALESCE(food,0)+?, steel=COALESCE(steel,0)+?, oil=COALESCE(oil,0)+?, rare=COALESCE(rare,0)+?, gold=COALESCE(gold,0)+?, version=version+1 WHERE player_id=? AND city_slot=?";
        try (var update = connection.prepareStatement(sql)) {
            int index = 1;
            for (String resource : new String[]{"food", "steel", "oil", "rare", "gold"}) {
                update.setLong(index++, Math.max(0, cost.getOrDefault(resource, 0)));
            }
            update.setLong(6, player); update.setInt(7, city);
            if (update.executeUpdate() == 0 && city != 0) {
                update.setInt(7, 0); update.executeUpdate();
            }
        }
    }
}
