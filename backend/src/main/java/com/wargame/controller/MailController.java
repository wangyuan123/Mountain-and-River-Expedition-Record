package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.MailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            @RequestParam(value = "folder", defaultValue = "inbox") String folder) {
        Long playerId = authService.getCurrentPlayer().getId();
        List<Map<String, Object>> mails = switch (folder) {
            case "outbox" -> mailService.listOutbox(playerId);
            case "system" -> mailService.listSystem(playerId);
            case "unread" -> mailService.listUnread(playerId);
            default       -> mailService.listInbox(playerId);
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folder", folder);
        out.put("mails", mails);
        out.put("unread", mailService.unreadCount(playerId));
        return ResponseEntity.ok(out);
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

    @PostMapping("/{id}/claim")
    public ResponseEntity<Map<String, Object>> claim(@PathVariable("id") Long id) {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = mailService.claimAttach(playerId, id);
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
