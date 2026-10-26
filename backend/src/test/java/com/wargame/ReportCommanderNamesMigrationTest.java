package com.wargame;

import com.wargame.util.JsonUtil;
import db.migration.V124__report_commander_names;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportCommanderNamesMigrationTest {
    @Test
    void repairsAllPagesAndSummariesWithoutChangingLogsOrResurrectingExpiredDetails() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE players(id BIGINT PRIMARY KEY,username VARCHAR(100),display_name VARCHAR(100))");
            sql.execute("CREATE TABLE scout_reports(id BIGINT PRIMARY KEY,data TEXT,summary_json TEXT,list_summary_json TEXT,read_at BIGINT,summary_expires_at BIGINT)");
            sql.execute("INSERT INTO players VALUES(1,'private_login','统帅$☆'),(2,'old_login',NULL)");
            String original = JsonUtil.toJson(Map.of("attackerName", "private_login", "defenderName", "old_login", "targetName", "private_login", "log", "private_login交战", "win", true));
            try (var insert = connection.prepareStatement("INSERT INTO scout_reports VALUES(?,?,?,?,10,20)")) {
                for (int id = 1; id <= 202; id++) {
                    insert.setInt(1, id); insert.setString(2, id == 202 ? null : original);
                    insert.setString(3, original); insert.setString(4, original); insert.addBatch();
                }
                insert.executeBatch();
            }
            sql.execute("INSERT INTO scout_reports VALUES(203,'malformed','{\"attackerName\":\"历史昵称\"}',NULL,11,21)");
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            new V124__report_commander_names().migrate(context);
            new V124__report_commander_names().migrate(context);
            try (var rows = sql.executeQuery("SELECT * FROM scout_reports ORDER BY id")) {
                for (int id = 1; id <= 202; id++) {
                    assertTrue(rows.next());
                    for (String column : new String[]{"data", "summary_json", "list_summary_json"}) {
                        if (id == 202 && column.equals("data")) { assertNull(rows.getString(column)); continue; }
                        var data = JsonUtil.parseObjMap(rows.getString(column));
                        assertEquals("统帅$☆", data.get("attackerName"));
                        assertEquals("统帅2", data.get("defenderName"));
                        assertEquals("private_login", data.get("targetName"));
                        assertEquals("private_login交战", data.get("log"));
                        assertEquals(true, data.get("win"));
                    }
                    assertEquals(10, rows.getLong("read_at"));
                    assertEquals(20, rows.getLong("summary_expires_at"));
                }
                assertTrue(rows.next());
                assertEquals("malformed", rows.getString("data"));
                assertEquals("{\"attackerName\":\"历史昵称\"}", rows.getString("summary_json"));
                assertNull(rows.getString("list_summary_json"));
            }
        }
    }
}
