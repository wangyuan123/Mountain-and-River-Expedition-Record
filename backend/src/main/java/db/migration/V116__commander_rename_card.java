package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 先检查历史重名再修改结构；补卡邮件与永久标记原子写入，支持 MySQL DDL 后失败重试。 */
public class V116__commander_rename_card extends BaseJavaMigration {
    private record NameRow(long id, String key) {}

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<NameRow> names = new ArrayList<>();
        Map<String, Long> owners = new HashMap<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, username, display_name FROM players ORDER BY id")) {
            while (rows.next()) {
                String displayName = rows.getString("display_name");
                String name = displayName == null || displayName.isBlank() ? rows.getString("username") : displayName;
                String key = Normalizer.normalize(Normalizer.normalize(name, Normalizer.Form.NFC)
                        .replaceAll("^ +| +$", "").toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
                long id = rows.getLong("id");
                Long previous = owners.putIfAbsent(key, id);
                if (previous != null) throw new SQLException("统帅名称重复，玩家ID：" + previous + "、" + id + "；请处理后重试迁移");
                names.add(new NameRow(id, key));
            }
        }
        boolean mysql = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
        addColumn(connection, "display_name_key", "VARCHAR(200)" + (mysql ? " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" : ""));
        addColumn(connection, "commander_renamed_at", "BIGINT NOT NULL DEFAULT 0");
        addColumn(connection, "commander_rename_count", "INT NOT NULL DEFAULT 0");
        addColumn(connection, "commander_rename_gift_sent", "BOOLEAN NOT NULL DEFAULT FALSE");
        try (var update = connection.prepareStatement("UPDATE players SET display_name_key=? WHERE id=?")) {
            for (NameRow row : names) {
                update.setString(1, row.key());
                update.setLong(2, row.id());
                update.addBatch();
            }
            update.executeBatch();
        }
        if (!hasIndex(connection, "uk_player_display_name_key")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE UNIQUE INDEX uk_player_display_name_key ON players(display_name_key)");
            }
        }
        issueGifts(connection);
    }

    private void addColumn(Connection connection, String name, String definition) throws SQLException {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, null, null)) {
            while (columns.next()) {
                if ("players".equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && name.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return;
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE players ADD COLUMN " + name + " " + definition);
        }
    }

    private boolean hasIndex(Connection connection, String name) throws SQLException {
        try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                if (!"players".equalsIgnoreCase(tables.getString("TABLE_NAME"))) continue;
                try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), tables.getString("TABLE_SCHEM"), tables.getString("TABLE_NAME"), true, false)) {
                    while (indexes.next()) {
                        if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
                    }
                }
            }
        }
        return false;
    }

    /** MySQL 不支持事务 DDL，结构完成后单独保证赠礼和标记同时提交或回滚。 */
    private void issueGifts(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        if (autoCommit) connection.setAutoCommit(false);
        String eligible = "game_initialized=TRUE AND is_bot=FALSE AND account_status<>'DELETED' AND commander_rename_gift_sent=FALSE";
        try {
            long now = System.currentTimeMillis();
            try (var insert = connection.prepareStatement("INSERT INTO mails "
                    + "(from_player_id,from_name,to_player_id,to_name,type,is_system,subject,body,attach_json,is_read,is_claimed,"
                    + "created_at,persisted_real_at,processed_retention_ms,unread_retention_ms,attachment_policy,attachment_source_type,attachment_source_id) "
                    + "SELECT NULL,'系统',id,username,'reward',TRUE,'统帅改名卡赠礼',?,? ,FALSE,FALSE,?,?,2592000000,5184000000,"
                    + "'PROTECTED','COMMANDER_RENAME_GIFT',CONCAT('commander-rename:',id) FROM players WHERE " + eligible)) {
                insert.setString(1, "为您补发1张统帅改名卡。请领取附件后前往仓库使用。首次用卡无需等待，此后每次成功改名需间隔30天（现实时间）；改名卡暂不在商城出售。");
                insert.setString(2, "[{\"type\":\"item\",\"itemId\":\"commanderRenameCard\",\"qty\":1}]");
                insert.setLong(3, now);
                insert.setLong(4, now);
                insert.executeUpdate();
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE players SET commander_rename_gift_sent=TRUE WHERE " + eligible);
            }
            if (autoCommit) connection.commit();
        } catch (SQLException failure) {
            if (autoCommit) connection.rollback();
            throw failure;
        } finally {
            if (autoCommit) connection.setAutoCommit(true);
        }
    }
}
