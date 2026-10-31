package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

/** 保存经济小数与真实加点，统一钱包、论坛统计和双方确认的外交关系。 */
public class V150__gameplay_consistency extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        // 先检查再执行DDL；历史异常资产不能通过截断或猜测补偿来掩盖。
        try (var q = c.createStatement(); var r = q.executeQuery(
                "SELECT player_id FROM resources GROUP BY player_id HAVING SUM(COALESCE(diamond,0)) > 2147483647 OR MIN(COALESCE(diamond,0)) < 0")) {
            if (r.next()) throw new SQLException("账号 " + r.getLong(1) + " 的历史钻石超出可合并范围，请核对账本后再升级");
        }
        boolean h2 = c.getMetaData().getDatabaseProductName().contains("H2");
        try (var s = c.createStatement()) {
            for (String stat : new String[]{"military", "defense", "logistics", "knowledge"})
                s.execute("ALTER TABLE officers ADD COLUMN assigned_" + stat + " INT NOT NULL DEFAULT 0");
            // 旧加点方向不可逆推；保留当前资质，不凭星级生成虚假的出生属性。
            s.execute("ALTER TABLE officers ADD COLUMN legacy_attr_allocation BOOLEAN NOT NULL DEFAULT TRUE");
            for (String table : new String[]{"players", "player_cities"}) {
                s.execute("ALTER TABLE " + table + " ADD COLUMN morale_accrual DOUBLE NOT NULL DEFAULT 0");
                s.execute("ALTER TABLE " + table + " ADD COLUMN resentment_accrual DOUBLE NOT NULL DEFAULT 0");
            }
            s.execute("ALTER TABLE forum_topics MODIFY COLUMN tip_total_gold BIGINT NOT NULL DEFAULT 0");
            s.execute("ALTER TABLE guild_relations ADD COLUMN friendship_requested_by BIGINT NULL");
            s.execute("ALTER TABLE guild_relations DROP " + (h2 ? "CONSTRAINT" : "CHECK") + " chk_guild_relations_status");
            s.execute("ALTER TABLE guild_relations ADD CONSTRAINT chk_guild_relations_status CHECK (status IN ('neutral','hostile','friendly'))");
            s.executeUpdate("DELETE FROM guild_applications WHERE NOT EXISTS (SELECT 1 FROM guilds g WHERE g.id=guild_applications.guild_id)");
            s.executeUpdate("DELETE FROM guild_relations WHERE NOT EXISTS (SELECT 1 FROM guilds g WHERE g.id=guild_relations.guild_low_id) OR NOT EXISTS (SELECT 1 FROM guilds g WHERE g.id=guild_relations.guild_high_id)");
            s.executeUpdate("UPDATE forum_topics SET tip_total_gold=(SELECT COALESCE(SUM(t.amount_gold),0) FROM forum_tips t WHERE t.topic_id=forum_topics.id)");
            s.executeUpdate("UPDATE market_orders SET status='CANCELLED', completed_at=CURRENT_TIMESTAMP WHERE status='ACTIVE' AND seller_id IN (SELECT id FROM players WHERE account_status='DELETED')");
        }
        // 旧友好缺少双方同意凭证，恢复中立后重新邀请；不能用当前入团信息猜测历史发起方。
        try (var s = c.createStatement()) {
            s.executeUpdate("UPDATE guild_relations SET status='neutral',friendship_requested_by=NULL WHERE status='friendly'");
        }
        var wallets = new ArrayList<long[]>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT r.player_id,SUM(COALESCE(r.diamond,0)) FROM resources r WHERE EXISTS (SELECT 1 FROM resources m WHERE m.player_id=r.player_id AND m.city_slot=0) GROUP BY r.player_id")) {
            while (r.next()) wallets.add(new long[]{r.getLong(1),r.getLong(2)});
        }
        try (var main = c.prepareStatement("UPDATE resources SET diamond=? WHERE player_id=? AND city_slot=0");
             var branches = c.prepareStatement("UPDATE resources SET diamond=0 WHERE player_id=? AND city_slot<>0")) {
            for (long[] wallet : wallets) {
                main.setLong(1,wallet[1]); main.setLong(2,wallet[0]); main.addBatch();
                branches.setLong(1,wallet[0]); branches.addBatch();
            }
            main.executeBatch(); branches.executeBatch();
        }
    }
}
