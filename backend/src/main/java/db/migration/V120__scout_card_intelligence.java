package db.migration;

import com.wargame.service.ReportSummary;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.util.ArrayList;
import java.util.List;

/** 补齐已有侦察卡片的侦察方式与守军快照；不改战报正文、期限、已读或收藏状态。 */
public class V120__scout_card_intelligence extends BaseJavaMigration {
    private record Card(long id, String json) {}

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        long cursor = 0;
        while (true) {
            List<Card> cards = new ArrayList<>();
            int scanned = 0;
            try (var select = connection.prepareStatement("SELECT id, data, summary_json FROM scout_reports WHERE type='scout' AND id>? ORDER BY id LIMIT 200")) {
                select.setLong(1, cursor);
                try (var rows = select.executeQuery()) {
                    while (rows.next()) {
                        scanned++;
                        cursor = rows.getLong("id");
                        // 正文已按保留期裁剪时，仍可从持久化历史摘要恢复情报，绝不读取当前野地守军。
                        String source = rows.getString("data");
                        if (source == null) source = rows.getString("summary_json");
                        String card = ReportSummary.cardJson(source);
                        if (card != null) cards.add(new Card(cursor, card));
                    }
                }
            }
            try (var update = connection.prepareStatement("UPDATE scout_reports SET list_summary_json=? WHERE id=?")) {
                for (Card card : cards) {
                    update.setString(1, card.json()); update.setLong(2, card.id()); update.addBatch();
                }
                update.executeBatch();
            }
            if (scanned < 200) break;
        }
    }
}
