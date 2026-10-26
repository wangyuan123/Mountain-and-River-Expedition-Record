package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class V122__notification_commander_names extends BaseJavaMigration {
    private static final Pattern PLAYER_NAME = Pattern.compile("玩家「([^」]+)」");
    private static final Pattern WAR_NOTICE = Pattern.compile("^⚔ (.+?) 对玩家「([^」]+)」宣战!$");
    private static final Pattern ITEM_NOTICE = Pattern.compile("^玩家「([^」]+)」打开了「");
    private record PlayerName(String login, String commander) {}
    private record MailUpdate(long id, String from, String to, String subject, String body) {}
    private record ChatUpdate(long id, String content) {}

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<Long, PlayerName> players = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, username, display_name FROM players")) {
            while (rows.next()) {
                long id = rows.getLong("id");
                String login = rows.getString("username");
                String display = rows.getString("display_name");
                String commander = display == null || display.isBlank() ? "统帅" + id : display;
                players.put(id, new PlayerName(login, commander));
                names.put(login, commander);
            }
        }
        List<MailUpdate> mails = new ArrayList<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, from_player_id, from_name, to_player_id, to_name, is_system, type, subject, body FROM mails")) {
            while (rows.next()) {
                String from = rows.getString("from_name");
                String to = rows.getString("to_name");
                PlayerName sender = players.get(rows.getLong("from_player_id"));
                PlayerName recipient = players.get(rows.getLong("to_player_id"));
                String subject = rows.getString("subject");
                String body = rows.getString("body");
                String nextFrom = sender != null && sender.login().equals(from) ? sender.commander() : from;
                String nextTo = recipient != null && recipient.login().equals(to) ? recipient.commander() : to;
                String nextSubject = subject;
                String nextBody = body;
                if (rows.getBoolean("is_system") && "combat".equals(rows.getString("type"))
                        && subject != null && (subject.startsWith("【战争警报】玩家「") || subject.startsWith("【宣战公告】已对玩家「"))) {
                    nextSubject = replacePlayerNames(subject, names);
                    nextBody = replacePlayerNames(body, names);
                }
                if (rows.getBoolean("is_system") && "alliance".equals(rows.getString("type"))
                        && "新的入团申请".equals(subject) && body != null) {
                    int separator = body.indexOf(" 申请加入军团「");
                    if (separator > 0) nextBody = names.getOrDefault(body.substring(0, separator), body.substring(0, separator)) + body.substring(separator);
                }
                if (!java.util.Objects.equals(from, nextFrom) || !java.util.Objects.equals(to, nextTo)
                        || !java.util.Objects.equals(subject, nextSubject) || !java.util.Objects.equals(body, nextBody)) {
                    mails.add(new MailUpdate(rows.getLong("id"), nextFrom, nextTo, nextSubject, nextBody));
                }
            }
        }
        try (var update = connection.prepareStatement("UPDATE mails SET from_name=?, to_name=?, subject=?, body=? WHERE id=?")) {
            for (MailUpdate mail : mails) {
                update.setString(1, mail.from());
                update.setString(2, mail.to());
                update.setString(3, mail.subject());
                update.setString(4, mail.body());
                update.setLong(5, mail.id());
                update.addBatch();
            }
            update.executeBatch();
        }
        List<ChatUpdate> chats = new ArrayList<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, content FROM chat_messages WHERE player_id IS NULL AND username='系统'")) {
            while (rows.next()) {
                String content = rows.getString("content");
                if (content == null) continue;
                Matcher war = WAR_NOTICE.matcher(content);
                Matcher item = ITEM_NOTICE.matcher(content);
                String next = content;
                if (war.matches()) {
                    next = "⚔ " + names.getOrDefault(war.group(1), war.group(1)) + " 对玩家「"
                            + names.getOrDefault(war.group(2), war.group(2)) + "」宣战!";
                } else if (item.find()) {
                    next = "玩家「" + names.getOrDefault(item.group(1), item.group(1)) + "」打开了「" + content.substring(item.end());
                }
                if (!content.equals(next)) chats.add(new ChatUpdate(rows.getLong("id"), next));
            }
        }
        try (var update = connection.prepareStatement("UPDATE chat_messages SET content=? WHERE id=?")) {
            for (ChatUpdate chat : chats) {
                update.setString(1, chat.content());
                update.setLong(2, chat.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private String replacePlayerNames(String content, Map<String, String> names) {
        if (content == null) return null;
        Matcher matcher = PLAYER_NAME.matcher(content);
        if (!matcher.find()) return content;
        return matcher.replaceFirst(Matcher.quoteReplacement("玩家「" + names.getOrDefault(matcher.group(1), matcher.group(1)) + "」"));
    }
}
