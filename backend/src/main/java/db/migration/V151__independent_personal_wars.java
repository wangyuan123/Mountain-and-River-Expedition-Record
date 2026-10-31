package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.LinkedHashMap;

/** 将仍有完整身份的最近个人战争迁入独立关系，不根据城市名称猜测已覆盖的旧对手。 */
public class V151__independent_personal_wars extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var c = context.getConnection();
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE personal_wars (id BIGINT AUTO_INCREMENT PRIMARY KEY, player_low_id BIGINT NOT NULL, player_high_id BIGINT NOT NULL, initiator_id BIGINT NOT NULL, city_slot INT NOT NULL, target_city_id BIGINT NOT NULL, declared_at BIGINT NOT NULL, war_at BIGINT NOT NULL, war_end_at BIGINT NOT NULL, accelerated BOOLEAN NOT NULL DEFAULT FALSE, order_effect TEXT, version BIGINT NOT NULL DEFAULT 0, CONSTRAINT uk_personal_wars_pair UNIQUE(player_low_id,player_high_id))");
            s.execute("CREATE INDEX idx_personal_wars_target ON personal_wars(target_city_id)");
        }
        var pairs = new LinkedHashMap<String, Object[]>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT p.id,p.war_against_id,p.war_initiator_id,p.war_city_slot,p.war_target_city_id,p.war_declared_at,p.war_at,p.war_end_at,p.war_accelerated,p.war_order_effect FROM players p JOIN players o ON o.id=p.war_against_id WHERE p.war_target_city_id IS NOT NULL AND p.war_end_at>0 AND p.id<>p.war_against_id AND p.account_status<>'DELETED' AND o.account_status<>'DELETED' ORDER BY p.war_declared_at,p.id")) {
            while (r.next()) {
                long low=Math.min(r.getLong(1),r.getLong(2)), high=Math.max(r.getLong(1),r.getLong(2));
                pairs.put(low+":"+high,new Object[]{low,high,r.getObject(3)==null?r.getLong(1):r.getLong(3),r.getInt(4),r.getLong(5),r.getLong(6),r.getLong(7),r.getLong(8),r.getBoolean(9),r.getString(10)});
            }
        }
        try (var insert=c.prepareStatement("INSERT INTO personal_wars(player_low_id,player_high_id,initiator_id,city_slot,target_city_id,declared_at,war_at,war_end_at,accelerated,order_effect) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (Object[] row:pairs.values()) { for(int i=0;i<row.length;i++) insert.setObject(i+1,row[i]); insert.addBatch(); }
            insert.executeBatch();
        }
    }
}
