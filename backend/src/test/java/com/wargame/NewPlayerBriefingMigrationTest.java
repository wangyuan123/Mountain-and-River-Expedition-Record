package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 验证旧通告定向替换，玩家邮件、其他系统通知和邮件状态均保留。 */
class NewPlayerBriefingMigrationTest {
    @Test
    void replacesOnlyLegacySeedBriefingsAndPreservesMailState() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:newPlayerBriefing;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mails(id BIGINT PRIMARY KEY, from_player_id BIGINT, from_name VARCHAR(50), "
                    + "to_player_id BIGINT, to_name VARCHAR(50), type VARCHAR(16), is_system BOOLEAN, "
                    + "subject VARCHAR(80), body TEXT, attach_json TEXT, is_read BOOLEAN, is_claimed BOOLEAN, created_at BIGINT)");
            String oldBody = "各位指挥官，东线斯大林格勒方向战事告急，盟军总指挥部号召各部加快战备建设，"
                    + "优先发展钢铁与石油产能。同时请保持对资源点的监控，避免被敌军抢占。";
            try (var insert = connection.prepareStatement("INSERT INTO mails VALUES(?, NULL, '盟军总指挥部', ?, 'test', "
                    + "'alliance', TRUE, '战时通告：东线战事升级', ?, '[]', ?, ?, ?)")) {
                for (int id = 1; id <= 10; id++) {
                    insert.setLong(1, id);
                    insert.setLong(2, 100 + id);
                    insert.setString(3, oldBody);
                    insert.setBoolean(4, id % 2 == 0);
                    insert.setBoolean(5, true);
                    insert.setLong(6, 1000 + id);
                    insert.executeUpdate();
                }
            }
            // 除两封旧模板外，逐项覆盖相似邮件，防止迁移误改玩家内容或其他通知。
            statement.execute("UPDATE mails SET from_player_id = 42, is_system = FALSE, type = 'player' WHERE id = 3");
            statement.execute("UPDATE mails SET body = '另一则战况通告' WHERE id = 4");
            statement.execute("UPDATE mails SET from_name = '其他指挥部' WHERE id = 5");
            statement.execute("UPDATE mails SET attach_json = '[{\"type\":\"gold\",\"qty\":100}]', is_claimed = FALSE WHERE id = 6");
            statement.execute("UPDATE mails SET is_system = FALSE WHERE id = 7");
            statement.execute("UPDATE mails SET from_player_id = 42 WHERE id = 8");
            statement.execute("UPDATE mails SET type = 'reward' WHERE id = 9");
            statement.execute("UPDATE mails SET subject = '其他战时通告' WHERE id = 10");

            var before = snapshot(connection);
            var migration = new ClassPathResource("db/migration/V104__new_player_burma_briefing.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            var after = snapshot(connection);
            assertEquals(before.keySet(), after.keySet());
            for (var entry : before.entrySet()) {
                var previous = entry.getValue();
                var current = after.get(entry.getKey());
                if (entry.getKey() > 2) {
                    assertEquals(previous, current, "相似邮件不应被替换: " + entry.getKey());
                    continue;
                }
                Set<String> changed = previous.keySet().stream()
                        .filter(key -> !Objects.equals(previous.get(key), current.get(key)))
                        .collect(Collectors.toSet());
                assertEquals(Set.of("FROM_NAME", "SUBJECT", "BODY"), changed);
                assertEquals("远征军司令部", current.get("FROM_NAME"));
                assertTrue(current.get("SUBJECT").toString().contains("滇缅"));
                String body = current.get("BODY").toString();
                assertTrue(body.contains("英美盟军协同对日作战"));
                assertTrue(body.contains("滇缅公路"));
            }
            ScriptUtils.executeSqlScript(connection, migration);
            assertEquals(after, snapshot(connection), "重复执行不应改变邮件或增发附件");
        }
    }

    private Map<Long, Map<String, Object>> snapshot(Connection connection) throws Exception {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM mails ORDER BY id")) {
            var metadata = rows.getMetaData();
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    row.put(metadata.getColumnName(column), rows.getObject(column));
                }
                result.put(rows.getLong("id"), row);
            }
        }
        return result;
    }
}
