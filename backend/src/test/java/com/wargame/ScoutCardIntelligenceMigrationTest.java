package com.wargame;

import com.wargame.service.ReportSummary;
import com.wargame.util.JsonUtil;
import db.migration.V120__scout_card_intelligence;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScoutCardIntelligenceMigrationTest {
    @Test
    void restoresHistoricalScoutCardsAcrossBatchesWithoutChangingReportsOrBattleCards() throws Exception {
        String scout = "{\"scoutUnit\":\"recon_car\",\"reconMode\":\"land\",\"showCityInfo\":true,\"army\":{\"infantry\":10},\"roundLogs\":[\"日志\"]}";
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE scout_reports(id BIGINT PRIMARY KEY, type VARCHAR(20), data TEXT, summary_json TEXT, list_summary_json TEXT, read_at BIGINT DEFAULT 77)");
            try (var insert = connection.prepareStatement("INSERT INTO scout_reports(id,type,data,summary_json,list_summary_json) VALUES(?,'scout',?,?,'{}')")) {
                for (int id = 1; id <= 205; id++) {
                    insert.setInt(1, id); insert.setString(2, id % 2 == 0 ? null : scout);
                    insert.setString(3, ReportSummary.fromJson(scout)); insert.addBatch();
                }
                insert.executeBatch();
            }
            statement.execute("INSERT INTO scout_reports(id,type,data,list_summary_json) VALUES(206,'scout','invalid','{\"preserved\":true}'),(207,'battle','{}','{\"battle\":true}')");
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            var migration = new V120__scout_card_intelligence(); migration.migrate(context); migration.migrate(context);
            try (var rows = statement.executeQuery("SELECT * FROM scout_reports WHERE id<=205 ORDER BY id")) {
                int count = 0;
                while (rows.next()) {
                    var card = JsonUtil.parseTree(rows.getString("list_summary_json"));
                    assertEquals("land", card.path("reconMode").asText());
                    assertEquals("recon_car", card.path("scoutUnit").asText());
                    assertEquals(10, card.path("army").path("infantry").asInt());
                    assertFalse(card.has("roundLogs")); assertEquals(77, rows.getLong("read_at"));
                    assertEquals(++count % 2 == 0 ? null : scout, rows.getString("data"));
                }
                assertEquals(205, count);
            }
            try (var rows = statement.executeQuery("SELECT list_summary_json FROM scout_reports WHERE id>=206 ORDER BY id")) {
                assertTrue(rows.next()); assertEquals("{\"preserved\":true}", rows.getString(1));
                assertTrue(rows.next()); assertEquals("{\"battle\":true}", rows.getString(1));
            }
        }
        String failed = scout.replace("\"showCityInfo\":true", "\"showCityInfo\":false");
        assertFalse(JsonUtil.parseTree(ReportSummary.cardJson(failed)).has("army"));
    }
}
