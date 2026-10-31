package com.wargame;

import com.wargame.security.AdminCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class AdminCredentialMigrationTest {
    @Test void migrationDoesNotDisableAnAdminWhoAlreadyChangedTheirPassword() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:admin_custom_credential_migration;MODE=MySQL", "sa", "")) {
            connection.createStatement().execute("CREATE TABLE admin_users(id BIGINT PRIMARY KEY, username VARCHAR(50), password_hash VARCHAR(255), disabled INT)");
            connection.createStatement().execute("INSERT INTO admin_users VALUES(1, 'admin', 'already-changed-hash', 0)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V142__admin_credential_version.sql"));
            var row = connection.createStatement().executeQuery("SELECT disabled,auth_version,password_hash FROM admin_users");
            assertTrue(row.next()); assertEquals(0, row.getInt(1)); assertEquals(1, row.getLong(2)); assertEquals("already-changed-hash", row.getString(3));
        }
    }

    @Test void migrationDisablesOnlyTheStockCredentialAndKeepsConfiguredAdministrators() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:admin_security_migration;MODE=MySQL", "sa", "")) {
            connection.createStatement().execute("CREATE TABLE admin_users(id BIGINT PRIMARY KEY, username VARCHAR(50), password_hash VARCHAR(255), disabled INT)");
            try (var insert = connection.prepareStatement("INSERT INTO admin_users VALUES(?,?,?,0)")) {
                insert.setLong(1, 1); insert.setString(2, "admin"); insert.setString(3, AdminCredentials.LEGACY_DEFAULT_HASH); insert.executeUpdate();
                insert.setLong(1, 2); insert.setString(2, "operator"); insert.setString(3, "configured-hash"); insert.executeUpdate();
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V142__admin_credential_version.sql"));
            var rows = connection.createStatement().executeQuery("SELECT disabled,auth_version,password_hash FROM admin_users ORDER BY id");
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals(2, rows.getLong(2));
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); assertEquals(1, rows.getLong(2)); assertEquals("configured-hash", rows.getString(3));
        }
    }
}
