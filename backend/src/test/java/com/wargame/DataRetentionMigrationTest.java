package com.wargame;

import com.wargame.config.RetentionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 在一次性 H2 MySQL 模式库验证回填规则；真实 MySQL 仍由 MySqlMigrationTest 验证。 */
class DataRetentionMigrationTest {
    @Test void legacyDataGetsGracePeriodsAndUnknownAssetsRemainProtected() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:retention_migration;MODE=MySQL", "sa", "")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            for (String table : new String[]{"chat_messages", "guild_chat_messages", "private_chat_messages"}) {
                jdbc.execute("CREATE TABLE " + table + "(id BIGINT PRIMARY KEY,created_at BIGINT,guild_id BIGINT,sender_id BIGINT,recipient_id BIGINT)");
                jdbc.update("INSERT INTO " + table + "(id,created_at) VALUES(1,1)");
            }
            jdbc.execute("CREATE TABLE mails(id BIGINT PRIMARY KEY,to_player_id BIGINT,from_player_id BIGINT,created_at BIGINT,surrender_offer_id BIGINT,is_read BOOLEAN,is_claimed BOOLEAN,attach_json TEXT)");
            jdbc.update("INSERT INTO mails VALUES(1,1,NULL,1,NULL,TRUE,TRUE,'[]'),(2,1,NULL,1,NULL,FALSE,FALSE,'[]'),(3,1,NULL,1,NULL,TRUE,FALSE,'[{\"type\":\"gold\",\"qty\":10}]'),(4,1,NULL,1,1,TRUE,TRUE,'[]')");
            jdbc.execute("CREATE TABLE scout_reports(id BIGINT PRIMARY KEY,player_id BIGINT,created_at BIGINT,data LONGTEXT)");
            jdbc.update("INSERT INTO scout_reports VALUES(1,1,1,'{\"roundLogs\":[\"旧战报\"]}')");
            jdbc.execute("CREATE TABLE shared_reports(token VARCHAR(36) PRIMARY KEY)");
            jdbc.execute("CREATE TABLE shared_incoming_reports(token VARCHAR(36) PRIMARY KEY)");
            jdbc.update("INSERT INTO shared_reports VALUES('old-share')");
            jdbc.update("INSERT INTO shared_incoming_reports VALUES('old-incoming')");
            jdbc.execute("CREATE TABLE pvp_practice_reports(id VARCHAR(36) PRIMARY KEY,player_id BIGINT,session_id VARCHAR(36))");
            jdbc.execute("CREATE TABLE pvp_practice_sessions(id VARCHAR(36) PRIMARY KEY,status VARCHAR(20),settled_at BIGINT)");
            jdbc.update("INSERT INTO pvp_practice_sessions VALUES('finished','FINISHED',1),('active','FIGHTING',0)");
            jdbc.execute("CREATE TABLE pvp_match_tickets(id VARCHAR(36) PRIMARY KEY,status VARCHAR(20))");
            jdbc.execute("CREATE TABLE pvp_requests(id VARCHAR(36) PRIMARY KEY,created_at BIGINT)");
            jdbc.execute("CREATE INDEX idx_pvp_request_expiry ON pvp_requests(created_at)");
            jdbc.execute("CREATE TABLE forum_topics(id BIGINT PRIMARY KEY,battle_report_id BIGINT)");
            jdbc.execute("CREATE TABLE city_surrender_offers(id BIGINT PRIMARY KEY,status VARCHAR(20))");
            jdbc.update("INSERT INTO city_surrender_offers VALUES(1,'PENDING'),(2,'REJECTED')");
            long before = System.currentTimeMillis();
            migrate(connection, "V112__data_retention_lifecycle.sql");
            migrate(connection, "V113__login_gift_activities.sql");

            var control = jdbc.queryForMap("SELECT * FROM data_retention_control");
            assertEquals(false, control.get("cleanup_enabled"));
            long startedAt = ((Number) control.get("observation_started_at")).longValue();
            // H2 的 UNIX_TIMESTAMP 只有秒精度，允许一秒舍入差异。
            assertTrue(startedAt >= before - 1000 && startedAt <= System.currentTimeMillis());
            for (String table : new String[]{"chat_messages", "guild_chat_messages", "private_chat_messages"})
                assertEquals(startedAt + 7 * RetentionPolicy.DAY, jdbc.queryForObject("SELECT retention_expires_at FROM " + table, Long.class));
            assertEquals(startedAt + 30 * RetentionPolicy.DAY, jdbc.queryForObject("SELECT retention_expires_at FROM mails WHERE id=1", Long.class));
            assertEquals(startedAt + 7 * RetentionPolicy.DAY, jdbc.queryForObject("SELECT retention_expires_at FROM mails WHERE id=2", Long.class));
            assertNull(jdbc.queryForObject("SELECT retention_expires_at FROM mails WHERE id=3", Long.class));
            assertEquals("LEGACY_UNKNOWN", jdbc.queryForObject("SELECT attachment_source_type FROM mails WHERE id=3", String.class));
            assertEquals("PROTECTED", jdbc.queryForObject("SELECT attachment_policy FROM mails WHERE id=3", String.class));
            assertNull(jdbc.queryForObject("SELECT retention_expires_at FROM mails WHERE id=4", Long.class));
            assertEquals(startedAt + 30 * RetentionPolicy.DAY, jdbc.queryForObject("SELECT detail_expires_at FROM scout_reports", Long.class));
            assertEquals(startedAt + 60 * RetentionPolicy.DAY, jdbc.queryForObject("SELECT summary_expires_at FROM scout_reports", Long.class));
            assertTrue(jdbc.queryForObject("SELECT data FROM scout_reports", String.class).contains("旧战报"));
            assertNull(jdbc.queryForObject("SELECT summary_json FROM scout_reports", String.class));
            assertEquals(startedAt + 7 * RetentionPolicy.DAY, jdbc.queryForObject("SELECT expires_at FROM shared_incoming_reports", Long.class));
            assertNull(jdbc.queryForObject("SELECT settled_real_at FROM pvp_practice_sessions WHERE id='active'", Long.class));
            assertNotNull(jdbc.queryForObject("SELECT retention_expires_at FROM pvp_practice_sessions WHERE id='finished'", Long.class));
            assertNull(jdbc.queryForObject("SELECT ended_real_at FROM city_surrender_offers WHERE id=1", Long.class));
            assertNotNull(jdbc.queryForObject("SELECT ended_real_at FROM city_surrender_offers WHERE id=2", Long.class));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM mail_gift_deliveries", Long.class));
        }
    }

    private void migrate(java.sql.Connection connection, String name) throws Exception {
        // H2 不支持 UNSIGNED，且 UNIX_TIMESTAMP 返回 INT；只适配测试端类型，不改迁移的生命周期 SQL。
        String sql = new ClassPathResource("db/migration/" + name).getContentAsString(StandardCharsets.UTF_8)
                .replace("AS UNSIGNED", "AS BIGINT")
                .replace("UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))", "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) AS BIGINT)");
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }
}
