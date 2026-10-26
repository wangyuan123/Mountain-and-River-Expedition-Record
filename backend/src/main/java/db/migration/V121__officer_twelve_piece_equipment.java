package db.migration;

import com.wargame.model.constants.OfficerEquipmentDef;
import org.flywaydb.core.api.migration.*;
import java.sql.*;
import java.util.*;

/** 停写升级：固定旧持有快照，补齐九部位并写幂等日志；不自动换装、不改变旧三件属性。 */
public class V121__officer_twelve_piece_equipment extends BaseJavaMigration {
    @Override public boolean canExecuteInTransaction() { return false; }

    @Override public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS officer_equipment_progress (id BIGINT AUTO_INCREMENT PRIMARY KEY, player_id BIGINT NOT NULL, progress_key VARCHAR(120) NOT NULL, amount INT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0, CONSTRAINT uk_officer_equipment_progress UNIQUE(player_id, progress_key), CONSTRAINT fk_equipment_progress_player FOREIGN KEY(player_id) REFERENCES players(id) ON DELETE CASCADE)");
        }
        Map<Long, Map<String, Integer>> owned = new LinkedHashMap<>();
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT player_id,item_key,count AS qty FROM player_items WHERE count>0 UNION ALL SELECT player_id,item_key,1 AS qty FROM officer_equipment WHERE officer_id IS NOT NULL")) {
            while (rows.next()) {
                String key = rows.getString("item_key");
                if (OfficerEquipmentDef.ITEMS.containsKey(key)) owned.computeIfAbsent(rows.getLong("player_id"), k -> new HashMap<>()).merge(key, rows.getInt("qty"), Math::addExact);
            }
        }
        boolean auto = c.getAutoCommit(); c.setAutoCommit(false);
        try {
            for (var player : owned.entrySet()) {
                long id = player.getKey(); var counts = player.getValue();
                for (String tier : List.of("recruit", "officer", "marshal")) for (String branch : OfficerEquipmentDef.BRANCHES) {
                    String set = tier + "_" + branch, marker = "migration/v121/" + set;
                    if (exists(c, id, marker)) continue;
                    int n = List.of("weapon", "badge", "coat").stream().mapToInt(slot -> counts.getOrDefault(set + "_" + slot, 0)).min().orElse(0);
                    for (var gear : OfficerEquipmentDef.setItems(set)) if (!List.of("weapon", "badge", "coat").contains(gear.slot()) && n > 0) {
                        try (var u = c.prepareStatement("UPDATE player_items SET count=count+?,version=version+1 WHERE player_id=? AND item_key=?")) {
                            u.setInt(1, n); u.setLong(2, id); u.setString(3, gear.key());
                            if (u.executeUpdate() == 0) try (var i = c.prepareStatement("INSERT INTO player_items(player_id,item_key,count,updated_at,version) VALUES(?,?,?,?,0)")) {
                                i.setLong(1, id); i.setString(2, gear.key()); i.setInt(3, n); i.setLong(4, System.currentTimeMillis()); i.executeUpdate();
                            }
                        }
                        put(c, id, "collection/" + gear.key(), 1);
                    }
                    put(c, id, marker, n); c.commit();
                }
                for (String key : counts.keySet()) put(c, id, "collection/" + key, 1);
                c.commit();
            }
        } catch (Exception e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(auto); }
    }
    private boolean exists(Connection c, long id, String key) throws SQLException {
        try (var s = c.prepareStatement("SELECT id FROM officer_equipment_progress WHERE player_id=? AND progress_key=?")) {
            s.setLong(1, id); s.setString(2, key); try (var r = s.executeQuery()) { return r.next(); }
        }
    }
    private void put(Connection c, long id, String key, int n) throws SQLException {
        if (exists(c, id, key)) return;
        try (var i = c.prepareStatement("INSERT INTO officer_equipment_progress(player_id,progress_key,amount,version) VALUES(?,?,?,0)")) {
            i.setLong(1, id); i.setString(2, key); i.setInt(3, n); i.executeUpdate();
        }
    }
}
