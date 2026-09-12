package com.wargame.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.ScoutReport;
import com.wargame.repository.ScoutReportRepository;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/game")
public class GameController {

    private final AuthService authService;
    private final GameStateService gameStateService;
    private final ScoutReportRepository scoutReportRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameController(AuthService authService, GameStateService gameStateService, ScoutReportRepository scoutReportRepository) {
        this.authService = authService;
        this.gameStateService = gameStateService;
        this.scoutReportRepository = scoutReportRepository;
    }

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getState() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(gameStateService.getGameState(playerId));
    }

    /**
     * 战报列表 - 返回最近 N 条战报(侦查 + 战斗), 供前端进入"战报"页时加载历史。
     * 侦查战报与战斗战报统一持久化在 scout_reports 表, 用 type 区分。
     */
    @GetMapping("/reports")
    public ResponseEntity<List<Map<String, Object>>> getReports(@RequestParam(defaultValue = "50") int limit) {
        Long playerId = authService.getCurrentPlayer().getId();
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<ScoutReport> reports = scoutReportRepository.findByPlayerIdOrderByCreatedAtDesc(playerId);
        List<Map<String, Object>> out = new ArrayList<>();
        int n = Math.min(safeLimit, reports.size());
        for (int i = 0; i < n; i++) {
            ScoutReport r = reports.get(i);
            String type = (r.getType() != null && !r.getType().isBlank()) ? r.getType() : "scout";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("type", type);
            item.put("time", r.getCreatedAt());
            item.put("readAt", r.getReadAt() != null ? r.getReadAt() : 0L);
            if ("battle".equals(type)) {
                // 战斗战报: data 存完整 JSON(win/subject/plunder/roundLogs 等), 提升到顶层供前端渲染
                Map<String, Object> battleData = parseData(r.getData(), r.getTargetName(), r.getTargetX(), r.getTargetY(), r.getCreatedAt());
                item.putAll(battleData);
                item.put("id", r.getId());
                item.put("type", "battle");
                item.put("time", r.getCreatedAt());
                item.put("readAt", r.getReadAt() != null ? r.getReadAt() : 0L);
            } else {
                item.put("data", parseData(r.getData(), r.getTargetName(), r.getTargetX(), r.getTargetY(), r.getCreatedAt()));
            }
            out.add(item);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/reports/unread")
    public ResponseEntity<Map<String, Object>> unreadReports() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(Map.of("unreadCount", scoutReportRepository.countUnreadByPlayerId(playerId)));
    }

    /** 标记单条战报已读。若已读过则 noop。 */
    @PostMapping("/reports/{id}/read")
    public ResponseEntity<Map<String, Object>> markReportRead(@org.springframework.web.bind.annotation.PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        ScoutReport r = scoutReportRepository.findById(id).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        if (r == null || !playerId.equals(r.getPlayerId())) {
            result.put("success", false);
            result.put("message", "战报不存在");
            return ResponseEntity.ok(result);
        }
        long now = System.currentTimeMillis();
        if (r.getReadAt() == null || r.getReadAt() == 0L) {
            r.setReadAt(now);
            scoutReportRepository.save(r);
        }
        result.put("success", true);
        result.put("readAt", r.getReadAt());
        result.put("unreadCount", scoutReportRepository.countUnreadByPlayerId(playerId));
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
        List<ScoutReport> reports = scoutReportRepository.findByPlayerIdOrderByCreatedAtDesc(playerId);
        int updated = 0;
        for (ScoutReport r : reports) {
            if (r.getReadAt() == null || r.getReadAt() == 0L) {
                r.setReadAt(now);
                scoutReportRepository.save(r);
                updated++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("updated", updated);
        result.put("unreadCount", scoutReportRepository.countUnreadByPlayerId(playerId));
        return ResponseEntity.ok(result);
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
        String cityName = request.cityName() == null ? "" : request.cityName().trim();
        if (cityName.isEmpty()) {
            cityName = "新城市";
        }
        if (!cityName.matches("^[A-Za-z0-9_\\u4e00-\\u9fa5·\\s]{1,12}$")) {
            throw new IllegalArgumentException("城市名仅限中英文/数字/下划线，最多12字");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        gameStateService.setCityName(playerId, cityName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/settings/avatar")
    public ResponseEntity<Map<String, Object>> setAvatar(@RequestBody GameDtos.AvatarRequest request) {
        String avatar = request.avatar() == null ? "" : request.avatar().trim();
        if (avatar.length() > 500) {
            throw new IllegalArgumentException("头像链接过长，限制在500字符以内");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        gameStateService.setAvatar(playerId, avatar);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("avatar", avatar);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
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
