package com.wargame.service;

import com.wargame.model.constants.MilitaryRankDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LeaderboardService {
    private static final int PAGE_SIZE = 20;
    private final JdbcTemplate jdbc;

    // 未初始化、注销及仍被封禁的账号不入榜，也不计入军团声望和人数。
    private static final String ELIGIBLE = """
                SELECT id, COALESCE(NULLIF(TRIM(display_name), ''), username) AS name,
                       avatar, COALESCE(prestige, 0) AS prestige,
                       COALESCE(military_rank, 1) AS military_rank
                FROM players
                WHERE game_initialized = true AND account_status = 'ACTIVE'
                  AND COALESCE(disabled, 0) = 0
                  AND (UPPER(COALESCE(ban_status, 'NORMAL')) <> 'BANNED'
                       OR (banned_until > 0 AND banned_until <= ?))
            """;

    private static final String PLAYERS = """
            SELECT p.*, COALESCE(g.name, '') AS guild_name
            FROM (
            """ + ELIGIBLE + """
            ) p
            LEFT JOIN guild_members m ON m.player_id = p.id
            LEFT JOIN guilds g ON g.id = m.guild_id
            """;

    private static final String GUILDS = """
            SELECT g.id, g.name, g.icon, COALESCE(leader.name, '') AS leader_name,
                   COALESCE(SUM(p.prestige), 0) AS prestige, COUNT(p.id) AS members
            FROM guilds g
            LEFT JOIN guild_members m ON m.guild_id = g.id
            LEFT JOIN (
            """ + ELIGIBLE + """
            ) p ON p.id = m.player_id
            LEFT JOIN (
            """ + ELIGIBLE + """
            ) leader ON leader.id = g.leader_player_id
            GROUP BY g.id, g.name, g.icon, leader.name
            HAVING COUNT(p.id) > 0
            """;

    public LeaderboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 返回分页榜单和全榜中的自身名次；同分以辅助指标、ID 排序，保证分页稳定。
     * @param playerId 当前登录玩家
     * @param type players 或 guilds
     * @param metric 玩家 prestige/militaryRank，军团 prestige/members
     * @param page 从 1 开始，超出范围时收敛至首尾页
     * @return 只含公开展示字段的榜单快照
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get(Long playerId, String type, String metric, int page) {
        boolean guilds = "guilds".equals(type);
        if (!guilds && !"players".equals(type)) throw new IllegalArgumentException("未知排行榜类型");
        String order;
        if ("prestige".equals(metric)) {
            order = guilds ? "prestige DESC, members DESC, id ASC" : "prestige DESC, military_rank DESC, id ASC";
        } else if (guilds && "members".equals(metric)) {
            order = "members DESC, prestige DESC, id ASC";
        } else if (!guilds && "militaryRank".equals(metric)) {
            order = "military_rank DESC, prestige DESC, id ASC";
        } else {
            throw new IllegalArgumentException("未知排行榜指标");
        }

        long now = System.currentTimeMillis();
        // 使用派生表保留 MySQL/H2 的预编译时间参数；H2 多层 CTE 会丢失内层参数。
        String scores = guilds ? GUILDS : PLAYERS;
        List<Object> parameters = new ArrayList<>(List.of(now));
        if (guilds) parameters.add(now);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + scores + ") scores",
                Long.class, parameters.toArray());
        int totalPages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        // 先在全榜生成名次，再筛选分页/自身；避免“我的排名”误用当前页的行号。
        String ranked = "SELECT scores.*, ROW_NUMBER() OVER (ORDER BY "
                + order + ") AS position FROM (" + scores + ") scores";
        RowMapper<Map<String, Object>> mapper = (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("rank", rs.getLong("position"));
            item.put("name", rs.getString("name"));
            item.put("prestige", rs.getLong("prestige"));
            if (guilds) {
                item.put("icon", rs.getString("icon"));
                item.put("leaderName", rs.getString("leader_name"));
                item.put("members", rs.getLong("members"));
            } else {
                int tier = rs.getInt("military_rank");
                item.put("avatar", rs.getString("avatar"));
                item.put("guildName", rs.getString("guild_name"));
                item.put("militaryRank", tier);
                item.put("militaryRankName", MilitaryRankDef.getRankName(tier));
            }
            return item;
        };
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(PAGE_SIZE);
        pageParameters.add((long) (currentPage - 1) * PAGE_SIZE);
        List<Map<String, Object>> entries = jdbc.query("SELECT * FROM (" + ranked +
                ") ranked ORDER BY position LIMIT ? OFFSET ?", mapper, pageParameters.toArray());
        String mineWhere = guilds
                ? "id = (SELECT guild_id FROM guild_members WHERE player_id = ?)" : "id = ?";
        List<Object> mineParameters = new ArrayList<>(parameters);
        mineParameters.add(playerId);
        List<Map<String, Object>> mine = jdbc.query("SELECT * FROM (" + ranked + ") ranked WHERE " + mineWhere,
                mapper, mineParameters.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("metric", metric);
        result.put("page", currentPage);
        result.put("pageSize", PAGE_SIZE);
        result.put("total", total);
        result.put("totalPages", totalPages);
        result.put("updatedAt", now);
        result.put("entries", entries);
        result.put("mine", mine.isEmpty() ? null : mine.get(0));
        return result;
    }
}
