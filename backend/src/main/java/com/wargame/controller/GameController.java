package com.wargame.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.AvatarDef;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.ScoutReport;
import com.wargame.repository.ScoutReportRepository;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.PrerequisiteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/game")
public class GameController {
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.config.RetentionPolicy retentionPolicy;

    private final AuthService authService;
    private final GameStateService gameStateService;
    @org.springframework.beans.factory.annotation.Autowired private PrerequisiteService prerequisiteService;
    private final ScoutReportRepository scoutReportRepository;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.service.ReportPrivacyService reportPrivacy;
    @org.springframework.beans.factory.annotation.Autowired private JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.repository.PlayerRepository playerRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameController(AuthService authService, GameStateService gameStateService, ScoutReportRepository scoutReportRepository) {
        this.authService = authService;
        this.gameStateService = gameStateService;
        this.scoutReportRepository = scoutReportRepository;
    }

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getState() {
        Long playerId = authService.getCurrentPlayer().getId();
        gameStateService.enterGame(playerId);
        return ResponseEntity.ok(gameStateService.getGameState(playerId));
    }

    /** Versioned prerequisites are served from the same rules used by build and research validation. */
    @GetMapping("/prerequisites")
    public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> getPrerequisites() {
        authService.getCurrentPlayer();
        return ResponseEntity.ok(prerequisiteService.rules());
    }

    /**
     * 战报列表 - 返回最近 N 条战报(侦查 + 战斗), 供前端进入"战报"页时加载历史。
     * 侦查战报与战斗战报统一持久化在 scout_reports 表, 用 type 区分。
     */
    @GetMapping("/reports")
    public ResponseEntity<?> getReports(@RequestParam(defaultValue = "20") int limit,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "false") boolean paged) {
        Long playerId = authService.getCurrentPlayer().getId();
        int safeLimit = Math.max(1, Math.min(100, limit));
        long now = System.currentTimeMillis();
        Long cursorTime = null, cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split(":", -1);
            try {
                if (parts.length != 2) throw new NumberFormatException();
                cursorTime = Long.parseLong(parts[0]);
                cursorId = Long.parseLong(parts[1]);
                if (cursorId < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("战报分页游标无效");
            }
        }
        String sql = "SELECT id, type, created_at, read_at, target_x, target_y, target_name, list_summary_json, " +
                "detail_expires_at, summary_expires_at, starred_at, (data IS NOT NULL) AS has_detail " +
                "FROM scout_reports WHERE player_id = ? AND (starred_at IS NOT NULL OR summary_expires_at > ?)" +
                (cursorTime == null ? "" : " AND (created_at < ? OR (created_at = ? AND id < ?))") +
                " ORDER BY created_at DESC, id DESC LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(playerId, now));
        if (cursorTime != null) { args.add(cursorTime); args.add(cursorTime); args.add(cursorId); }
        args.add(safeLimit + (paged ? 1 : 0));
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, rowNum) -> {
            long id = rs.getLong("id");
            long time = rs.getLong("created_at");
            String type = rs.getString("type");
            if (type == null || type.isBlank()) type = "scout";
            String summaryJson = rs.getString("list_summary_json");
            Map<String, Object> summary = parseData(summaryJson, rs.getString("target_name"),
                    (Integer) rs.getObject("target_x"), (Integer) rs.getObject("target_y"), time);
            long detailExpiry = rs.getLong("detail_expires_at");
            boolean starred = rs.getObject("starred_at") != null;
            boolean detailsAvailable = rs.getBoolean("has_detail") && (starred || detailExpiry > now);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id); item.put("type", type); item.put("time", time);
            item.put("readAt", rs.getObject("read_at") == null ? 0L : rs.getLong("read_at"));
            item.put("detailsAvailable", detailsAvailable); item.put("detailsExpired", !detailsAvailable);
            item.put("starred", starred);
            if ("battle".equals(type)) {
                item.putAll(summary);
                item.put("id", id); item.put("type", type); item.put("time", time);
                item.put("readAt", rs.getObject("read_at") == null ? 0L : rs.getLong("read_at"));
                item.put("detailsAvailable", detailsAvailable); item.put("detailsExpired", !detailsAvailable);
                item.put("starred", starred);
            } else {
                item.put("data", summary);
            }
            item.put("summaryOnly", true);
            item.put("summaryPending", summaryJson == null);
            item.put("detailExpiresAt", detailExpiry);
            item.put("summaryExpiresAt", rs.getLong("summary_expires_at"));
            return item;
        }, args.toArray());
        reportPrivacy.anonymize(rows);
        if (!paged) return ResponseEntity.ok(rows);
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows.remove(rows.size() - 1);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", rows); page.put("hasMore", hasMore);
        Map<String, Object> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        page.put("nextCursor", hasMore ? last.get("time") + ":" + last.get("id") : null);
        page.put("retentionPolicy", Map.of("detailDays", retentionPolicy.getReportDetailDays(), "summaryDays", retentionPolicy.getReportSummaryDays(), "starLimit", 50));
        return ResponseEntity.ok(page);
    }

    /**
     * 按目标读取本账号最近仍在保留期内的成功侦察，不依赖前端加载过哪一页战报。
     * 仅返回已有快照，不读取目标当前守军，也不将查看情报计为阅读战报。
     */
    @GetMapping("/reports/scout-intelligence")
    public ResponseEntity<Map<String, Object>> getScoutIntelligence(@RequestParam int x, @RequestParam int y) {
        Long playerId = authService.getCurrentPlayer().getId();
        int size = com.wargame.model.constants.WorldConfig.WORLD_SIZE;
        if (x < 0 || y < 0 || x >= size || y >= size) throw new IllegalArgumentException("目标坐标无效");
        long now = System.currentTimeMillis();
        Long cursorTime = null, cursorId = null;
        while (true) {
            String sql = "SELECT id, created_at, target_name, list_summary_json, summary_expires_at, starred_at " +
                    "FROM scout_reports WHERE player_id = ? AND target_x = ? AND target_y = ? AND type = 'scout' " +
                    "AND (starred_at IS NOT NULL OR summary_expires_at > ?)" +
                    (cursorTime == null ? "" : " AND (created_at < ? OR (created_at = ? AND id < ?))") +
                    " ORDER BY created_at DESC, id DESC LIMIT 20";
            List<Object> args = new ArrayList<>(List.of(playerId, x, y, now));
            if (cursorTime != null) { args.add(cursorTime); args.add(cursorTime); args.add(cursorId); }
            List<Map<String, Object>> rows = jdbc.query(sql, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id")); row.put("type", "scout"); row.put("time", rs.getLong("created_at"));
                row.put("data", parseData(rs.getString("list_summary_json"), rs.getString("target_name"), x, y, rs.getLong("created_at")));
                row.put("starred", rs.getObject("starred_at") != null);
                row.put("summaryExpiresAt", rs.getLong("summary_expires_at"));
                return row;
            }, args.toArray());
            for (Map<String, Object> row : rows) {
                Map<?, ?> data = (Map<?, ?>) row.get("data");
                if (data != null && Boolean.TRUE.equals(data.get("showCityInfo")) && data.get("army") instanceof Map) {
                    reportPrivacy.anonymize(List.of(row));
                    return ResponseEntity.ok(Map.of("found", true, "report", row));
                }
            }
            // 后来的失败侦察不能覆盖较早的成功情报；按游标分批查找，避免加载完整日志或所有战报。
            if (rows.size() < 20) return ResponseEntity.ok(Map.of("found", false));
            Map<String, Object> last = rows.get(rows.size() - 1);
            cursorTime = (Long) last.get("time"); cursorId = (Long) last.get("id");
        }
    }

    /** 战报列表只含快照摘要；点击详情时按 ID 单独加载完整 JSON。 */
    @GetMapping("/reports/{id}")
    public ResponseEntity<Map<String, Object>> getReportDetail(@org.springframework.web.bind.annotation.PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        long now = System.currentTimeMillis();
        ScoutReport report = scoutReportRepository.findById(id)
                .filter(r -> playerId.equals(r.getPlayerId()) && (r.getStarredAt() != null || r.getSummaryExpiresAt() != null && r.getSummaryExpiresAt() > now))
                .orElseThrow(() -> new IllegalArgumentException("战报不存在或已过期"));
        boolean detailsAvailable = report.getData() != null &&
                (report.getStarredAt() != null || report.getDetailExpiresAt() == null || report.getDetailExpiresAt() > now);
        String json = detailsAvailable ? report.getData() : report.getSummaryJson();
        Map<String, Object> snapshot = parseData(json, report.getTargetName(), report.getTargetX(), report.getTargetY(), report.getCreatedAt());
        String type = report.getType() == null ? "scout" : report.getType();
        Map<String, Object> data = new LinkedHashMap<>();
        if ("battle".equals(type)) data.putAll(snapshot);
        else data.put("data", snapshot);
        data.put("id", report.getId()); data.put("type", type);
        data.put("time", report.getCreatedAt()); data.put("readAt", report.getReadAt() == null ? 0L : report.getReadAt());
        data.put("detailsAvailable", detailsAvailable); data.put("detailsExpired", !detailsAvailable);
        data.put("starred", report.getStarredAt() != null);
        data.put("detailExpiresAt", report.getDetailExpiresAt());
        data.put("summaryExpiresAt", report.getSummaryExpiresAt());
        data.put("summaryUnavailable", !detailsAvailable && report.getData() != null && !com.wargame.service.ReportSummary.canTrim(report.getData(), report.getSummaryJson(), type));
        reportPrivacy.anonymize(List.of(data));
        return ResponseEntity.ok(data);
    }

    @GetMapping("/reports/unread")
    public ResponseEntity<Map<String, Object>> unreadReports() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(Map.of("unreadCount", scoutReportRepository.countVisibleUnreadByPlayerId(playerId, System.currentTimeMillis())));
    }

    /** 标记单条战报已读。若已读过则 noop。 */
    @PostMapping("/reports/{id}/read")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> markReportRead(@org.springframework.web.bind.annotation.PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        ScoutReport r = scoutReportRepository.findByIdForUpdate(id).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        if (r == null || !playerId.equals(r.getPlayerId()) || (r.getStarredAt() == null && (r.getSummaryExpiresAt() == null || r.getSummaryExpiresAt() <= now))) {
            result.put("success", false);
            result.put("message", "战报不存在或已过期");
            return ResponseEntity.ok(result);
        }
        if (r.getReadAt() == null || r.getReadAt() == 0L) {
            r.setReadAt(now);
            scoutReportRepository.save(r);
        }
        result.put("success", true);
        result.put("readAt", r.getReadAt());
        result.put("unreadCount", scoutReportRepository.countVisibleUnreadByPlayerId(playerId, now));
        return ResponseEntity.ok(result);
    }

    /**
     * 一键全部已读。把当前玩家所有未读战报置为已读。
     */
    @PostMapping("/reports/read-all")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> markAllReportsRead() {
        Long playerId = authService.getCurrentPlayer().getId();
        long now = System.currentTimeMillis();
        int updated = scoutReportRepository.markAllVisibleRead(playerId, now);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("updated", updated);
        result.put("unreadCount", scoutReportRepository.countVisibleUnreadByPlayerId(playerId, now));
        return ResponseEntity.ok(result);
    }

    /** 收藏保留完整详情，且按每个接收账号最多五十份限制。 */
    @PostMapping("/reports/{id}/star")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> starReport(@org.springframework.web.bind.annotation.PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        long now = System.currentTimeMillis();
        ScoutReport report = scoutReportRepository.findByIdForUpdate(id)
                .filter(r -> playerId.equals(r.getPlayerId()))
                .orElseThrow(() -> new IllegalArgumentException("战报不存在或无权收藏"));
        if (report.getStarredAt() == null && (report.getData() == null || report.getDetailExpiresAt() == null || report.getDetailExpiresAt() <= now)) {
            throw new IllegalArgumentException("战报详情已过期，无法收藏");
        }
        if (report.getStarredAt() == null && scoutReportRepository.countByPlayerIdAndStarredAtIsNotNull(playerId) >= 50) {
            throw new IllegalArgumentException("每个账号最多收藏 50 份战报，请先取消收藏");
        }
        if (report.getStarredAt() == null) {
            report.setStarredAt(now);
            scoutReportRepository.save(report);
        }
        return ResponseEntity.ok(Map.of("success", true, "starred", true));
    }

    @DeleteMapping("/reports/{id}/star")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> unstarReport(@org.springframework.web.bind.annotation.PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        ScoutReport report = scoutReportRepository.findByIdForUpdate(id)
                .filter(r -> playerId.equals(r.getPlayerId()))
                .orElseThrow(() -> new IllegalArgumentException("战报不存在或无权取消收藏"));
        if (report.getStarredAt() != null) {
            report.setStarredAt(null);
            // 收藏时保留原期限，取消时恢复原规则，不受后续配置变更影响。
            scoutReportRepository.save(report);
        }
        return ResponseEntity.ok(Map.of("success", true, "starred", false));
    }

    /** 容错解析 data JSON: 失败时用 entity 字段拼一个最小可显示对象 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseData(String data, String targetName, Integer tx, Integer ty, Long createdAt) {
        if (data == null || data.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", createdAt);
            m.put("targetName", targetName);
            m.put("x", tx);
            m.put("y", ty);
            m.put("result", "unknown");
            m.put("showCityInfo", false);
            m.put("myScouts", 0);
            m.put("myLost", 0);
            m.put("enemyScouts", 0);
            m.put("enemyLost", 0);
            return m;
        }
        try {
            return objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return parseData(null, targetName, tx, ty, createdAt);
        }
    }

    @PostMapping("/settings/city-name")
    public ResponseEntity<Map<String, Object>> setCityName(@RequestBody GameDtos.CityNameRequest request) {
        String rawName = request.cityName();
        String cityName = (rawName == null || rawName.trim().isEmpty()) ? "新城市" : rawName;
        cityName = com.wargame.model.constants.DisplayNamePolicy.validate(cityName, 12, "城市名称");
        Long playerId = authService.getCurrentPlayer().getId();
        gameStateService.setCityName(playerId, cityName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/settings/display-name")
    public ResponseEntity<Map<String, Object>> setDisplayName(@RequestBody GameDtos.DisplayNameRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        gameStateService.setDisplayName(playerId, request.name());
        return ResponseEntity.ok(Map.of("success", true, "state", gameStateService.getGameState(playerId)));
    }

    @PostMapping("/settings/avatar")
    public ResponseEntity<Map<String, Object>> setAvatar(@RequestBody GameDtos.AvatarRequest request) {
        String avatar = request.avatar() == null ? "" : request.avatar().trim();
        // 保存接口与新头像库共用白名单，旧版 SVG 已下线。
        if (!AvatarDef.isPreset(avatar)) {
            throw new IllegalArgumentException("请选择预设指挥官头像");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        gameStateService.setAvatar(playerId, avatar);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("avatar", avatar);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/settings/home-modules")
    public ResponseEntity<Map<String, Object>> setHomeModuleOrder(@RequestBody GameDtos.HomeModuleOrderRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        List<String> order = gameStateService.setHomeModuleOrder(playerId, request == null ? null : request.order());
        return ResponseEntity.ok(Map.of("success", true, "homeModuleOrder", order));
    }

    @PostMapping("/settings/tax")
    public ResponseEntity<Map<String, Object>> setTax(@RequestBody GameDtos.TaxRequest request) {
        if (request.tax() == null) {
            throw new IllegalArgumentException("税率不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = gameStateService.setTax(playerId, request.tax());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/city/appease")
    public ResponseEntity<Map<String, Object>> appease(@RequestBody GameDtos.AppeaseRequest request) {
        if (request.type() == null || request.type().isBlank()) {
            throw new IllegalArgumentException("安抚类型不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = gameStateService.appease(playerId, request.type());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/settings/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody GameDtos.ResetRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", "正式环境不可重建初始数据，重置功能不可用");
        return ResponseEntity.ok(result);
    }
}
