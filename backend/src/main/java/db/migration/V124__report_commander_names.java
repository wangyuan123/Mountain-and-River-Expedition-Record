package db.migration;

import com.wargame.util.JsonUtil;
import com.wargame.util.PlayerNames;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 只修复历史战报身份字段中精确匹配的登录名，保留战斗日志、战果、期限和已裁剪状态。 */
public class V124__report_commander_names extends BaseJavaMigration {
    private record Row(long id, String data, String summary, String card) {}

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        Map<String, String> names = new HashMap<>();
        try (var select = connection.createStatement();
             var players = select.executeQuery("SELECT id, username, display_name FROM players")) {
            while (players.next()) names.put(players.getString("username"),
                    PlayerNames.commanderName(players.getLong("id"), players.getString("display_name")));
        }
        long cursor = 0;
        while (true) {
            var changed = new ArrayList<Row>();
            int scanned = 0;
            try (var select = connection.prepareStatement("SELECT id, data, summary_json, list_summary_json FROM scout_reports WHERE id>? ORDER BY id LIMIT 200")) {
                select.setLong(1, cursor);
                try (var rows = select.executeQuery()) {
                    while (rows.next()) {
                        scanned++; cursor = rows.getLong("id");
                        String data = rows.getString("data"), summary = rows.getString("summary_json"), card = rows.getString("list_summary_json");
                        String nextData = repair(data, names), nextSummary = repair(summary, names), nextCard = repair(card, names);
                        if (!Objects.equals(data, nextData) || !Objects.equals(summary, nextSummary) || !Objects.equals(card, nextCard))
                            changed.add(new Row(cursor, nextData, nextSummary, nextCard));
                    }
                }
            }
            try (var update = connection.prepareStatement("UPDATE scout_reports SET data=?, summary_json=?, list_summary_json=? WHERE id=?")) {
                for (Row row : changed) {
                    update.setString(1, row.data()); update.setString(2, row.summary()); update.setString(3, row.card());
                    update.setLong(4, row.id()); update.addBatch();
                }
                update.executeBatch();
            }
            if (scanned < 200) break;
        }
    }

    /** 不做全文替换，玩家自填城市名和历史日志中出现的同名文本不属于身份字段。 */
    private String repair(String json, Map<String, String> names) {
        if (json == null) return null;
        try {
            var node = JsonUtil.getMapper().readTree(json);
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return json;
            boolean changed = false;
            for (String field : new String[]{"attackerName", "defenderName", "fromName", "toName"}) {
                var value = object.get(field);
                if (value != null && value.isTextual() && names.containsKey(value.asText())) {
                    String next = names.get(value.asText());
                    if (!next.equals(value.asText())) { object.put(field, next); changed = true; }
                }
            }
            return changed ? JsonUtil.getMapper().writeValueAsString(object) : json;
        } catch (Exception ignored) { return json; }
    }
}
