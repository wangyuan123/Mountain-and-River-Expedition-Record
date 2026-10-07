package com.wargame.controller;

import com.wargame.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/game/reports")
public class ReportShareController {
    private final ReportShareService share;
    private final AuthService auth;
    public record Request(String channel, Long recipientId) {}
    @PostMapping("/{id}/share")
    public Map<String, Object> send(@PathVariable Long id, @RequestBody Request request) {
        share.share(auth.getCurrentPlayer().getId(), id, request.channel(), request.recipientId());
        return Map.of("success", true);
    }
    @GetMapping("/shared/{token}")
    public Map<String, Object> read(@PathVariable String token) { return share.read(token); }
}
