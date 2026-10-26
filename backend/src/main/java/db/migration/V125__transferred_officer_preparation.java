package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** 按军官当前归属修复历史整备；不转移图鉴、账号方案或已不存在军官的数据。 */
public class V125__transferred_officer_preparation extends BaseJavaMigration {
    private static final Pattern KEY = Pattern.compile("prep/([0-9]+)/[^/]+");
    private record Row(long id, long player, String key, int amount) {}

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        long cursor = 0;
        while (true) {
            var page = new ArrayList<Row>();
            try (var query = connection.prepareStatement("SELECT id,player_id,progress_key,amount FROM officer_equipment_progress WHERE id>? AND progress_key LIKE 'prep/%' ORDER BY id LIMIT 200")) {
                query.setLong(1, cursor);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) page.add(new Row(rows.getLong("id"), rows.getLong("player_id"), rows.getString("progress_key"), rows.getInt("amount")));
                }
            }
            for (var row : page) {
                cursor = row.id();
                var match = KEY.matcher(row.key());
                if (!match.matches()) continue;
                long officer;
                try { officer = Long.parseLong(match.group(1)); }
                catch (NumberFormatException invalid) { continue; }
                Long owner = null;
                try (var query = connection.prepareStatement("SELECT player_id FROM officers WHERE id=?")) {
                    query.setLong(1, officer);
                    try (var rows = query.executeQuery()) { if (rows.next()) owner = rows.getLong(1); }
                }
                if (owner == null || owner == row.player()) continue;
                Long existingId = null; int amount = row.amount();
                try (var query = connection.prepareStatement("SELECT id,amount FROM officer_equipment_progress WHERE player_id=? AND progress_key=?")) {
                    query.setLong(1, owner); query.setString(2, row.key());
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) { existingId = rows.getLong(1); amount = Math.max(amount, rows.getInt(2)); }
                    }
                }
                // 同键只保留最高等级，不能相加而产生额外培养。
                if (existingId == null) {
                    try (var update = connection.prepareStatement("UPDATE officer_equipment_progress SET player_id=?,version=version+1 WHERE id=?")) {
                        update.setLong(1, owner); update.setLong(2, row.id()); update.executeUpdate();
                    }
                } else {
                    try (var update = connection.prepareStatement("UPDATE officer_equipment_progress SET amount=?,version=version+1 WHERE id=?")) {
                        update.setInt(1, amount); update.setLong(2, existingId); update.executeUpdate();
                    }
                    try (var delete = connection.prepareStatement("DELETE FROM officer_equipment_progress WHERE id=?")) {
                        delete.setLong(1, row.id()); delete.executeUpdate();
                    }
                }
            }
            if (page.size() < 200) break;
        }
    }
}
