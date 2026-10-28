package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class AccountEmailMigrationTest {
    @Test void migrationPreservesAccountsAndRemovesPlaintextPasswords() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:email-migration;MODE=MySQL", "sa", "");
             var sql = connection.createStatement()) {
            sql.execute("create table players(id bigint primary key, username varchar(50), password_hash varchar(255), plain_password varchar(128))");
            sql.execute("insert into players values (1, 'isolated-old-account', 'existing-hash', 'isolated-plaintext')");
            sql.execute("create table admin_operation_logs(action_type varchar(50), detail text)");
            sql.execute("insert into admin_operation_logs values ('RESET_PLAYER_PASSWORD', 'isolated old detail')");
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.support.EncodedResource(
                    new ClassPathResource("db/migration/V137__qq_email_account_recovery.sql"), java.nio.charset.StandardCharsets.UTF_8));
            try (var row = sql.executeQuery("select * from players where id=1")) {
                assertTrue(row.next()); assertEquals("existing-hash", row.getString("password_hash"));
                assertNull(row.getString("recovery_email_key")); assertEquals(0, row.getLong("recovery_email_version"));
            }
            try (var columns = connection.getMetaData().getColumns(null, null, "PLAYERS", "PLAIN_PASSWORD")) { assertFalse(columns.next()); }
            try (var rows = sql.executeQuery("select detail from admin_operation_logs")) {
                assertTrue(rows.next()); assertEquals("历史密码重置记录（凭据内容已清理）", rows.getString(1));
            }
        }
    }
}
