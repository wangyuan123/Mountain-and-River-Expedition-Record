package db.migration;

import com.wargame.model.constants.CommanderRenamePolicy;
import com.wargame.util.PlayerNames;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;

/** 将旧账号已在页头公开的名称补为统帅名，并定向修复历史开箱公告的默认名。 */
public class V148__legacy_commander_names extends BaseJavaMigration {
    private static final Pattern ITEM_NOTICE = Pattern.compile("^玩家「([^」]+)」打开了「");
    private record PlayerName(long id, String name, boolean missing) {}
    private record ChatUpdate(long id, String content) {}

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var players = new ArrayList<PlayerName>();
        var publicNames = new HashSet<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, username, display_name FROM players")) {
            while (rows.next()) {
                String displayName = rows.getString("display_name");
                boolean missing = displayName == null || displayName.isBlank();
                String name = missing ? rows.getString("username") : displayName;
                if (name == null || name.isBlank()) name = PlayerNames.commanderName(rows.getLong("id"), null);
                players.add(new PlayerName(rows.getLong("id"), name, missing));
                publicNames.add(CommanderRenamePolicy.nameKey(name));
            }
        }
        // 补齐历史展示字段，不算用卡改名，保留改名次数、冷却和已有唯一键语义。
        try (var update = connection.prepareStatement("UPDATE players SET display_name=?, display_name_key=? WHERE id=?")) {
            for (PlayerName player : players) {
                if (!player.missing()) continue;
                update.setString(1, player.name());
                update.setString(2, CommanderRenamePolicy.nameKey(player.name()));
                update.setLong(3, player.id());
                update.addBatch();
            }
            update.executeBatch();
        }
        Map<String, String> aliases = new HashMap<>();
        for (PlayerName player : players) {
            String fallback = PlayerNames.commanderName(player.id(), null);
            // 默认名若恰好是其他玩家的真实名称，历史纯文本无法消歧，保留原文。
            if (!publicNames.contains(CommanderRenamePolicy.nameKey(fallback))) aliases.put(fallback, player.name());
        }
        long cursor = 0;
        while (true) {
            var changed = new ArrayList<ChatUpdate>();
            int scanned = 0;
            try (var select = connection.prepareStatement("SELECT id, content FROM chat_messages WHERE id>? AND player_id IS NULL AND username='系统' ORDER BY id LIMIT 200")) {
                select.setLong(1, cursor);
                try (var rows = select.executeQuery()) {
                    while (rows.next()) {
                        scanned++;
                        cursor = rows.getLong("id");
                        String content = rows.getString("content");
                        if (content == null) continue;
                        var matcher = ITEM_NOTICE.matcher(content);
                        // 只替换系统开箱模板的玩家字段；道具、奖励和玩家自由文本均不改写。
                        if (matcher.find() && aliases.containsKey(matcher.group(1))) {
                            changed.add(new ChatUpdate(cursor, "玩家「" + aliases.get(matcher.group(1))
                                    + "」打开了「" + content.substring(matcher.end())));
                        }
                    }
                }
            }
            try (var update = connection.prepareStatement("UPDATE chat_messages SET content=? WHERE id=?")) {
                for (ChatUpdate row : changed) {
                    update.setString(1, row.content());
                    update.setLong(2, row.id());
                    update.addBatch();
                }
                update.executeBatch();
            }
            if (scanned < 200) break;
        }
    }
}
