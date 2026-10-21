package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.MailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * 邮件 REST 接口。
 * <pre>
 *   GET  /api/game/mail/list?folder=inbox|outbox|system|unread
 *   GET  /api/game/mail/unread-count
 *   POST /api/game/mail/send      { to, subject, body, attach: [{type, qty}] }
 *   POST /api/game/mail/{id}/read
 *   POST /api/game/mail/{id}/claim
 *   DELETE /api/game/mail/{id}
 * </pre>
 */
@RestController
@RequestMapping("/api/game/mail")
public class MailController {

    private final MailService mailService;
    private final AuthService authService;
    private final GameStateService gameStateService;

    public MailController(MailService mailService,
                          AuthService authService,
                          GameStateService gameStateService) {
        this.mailService = mailService;
        this.authService = authService;
        this.gameStateService = gameStateService;
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "folder", defaultValue = "inbox") String folder,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        Long playerId = authService.getCurrentPlayer().getId();
        if (!java.util.Set.of("inbox", "outbox", "system", "unread").contains(folder)) {
            throw new IllegalArgumentException("邮件文件夹无效");
        }
        return ResponseEntity.ok(mailService.listPage(playerId, folder, cursor, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(mailService.detail(playerId, id));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> unreadCount() {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unread", mailService.unreadCount(playerId));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, Object> body) {
        Long playerId = authService.getCurrentPlayer().getId();
        String to = (String) body.get("to");
        String subject = (String) body.get("subject");
        String text = (String) body.get("body");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attach = (List<Map<String, Object>>) body.getOrDefault("attach", new ArrayList<>());
        Map<String, Object> result = mailService.send(playerId, to, subject, text, attach);
        // 资源可能已扣减, 顺手把最新 state 一起返回, 前端无需再发 /game/state
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> read(@PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        mailService.markRead(playerId, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("unread", mailService.unreadCount(playerId));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll() {
        Long playerId = authService.getCurrentPlayer().getId();
        int updated = mailService.markAllRead(playerId);
        return Map.of("success", true, "updated", updated, "unread", mailService.unreadCount(playerId));
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<Map<String, Object>> claim(@PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = new LinkedHashMap<>(mailService.claimAttach(playerId, id));
        // 附件已入账 resources, 随响应返回最新 state
        result.put("state", gameStateService.getGameState(playerId));
        result.put("unread", mailService.unreadCount(playerId));
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        mailService.delete(playerId, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("unread", mailService.unreadCount(playerId));
        return ResponseEntity.ok(out);
    }
}
