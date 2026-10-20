package com.wargame.controller;

import com.wargame.model.dto.ChatDtos;
import com.wargame.service.AuthService;
import com.wargame.service.IncomingReportShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/game/incoming")
public class IncomingReportShareController {
    private final IncomingReportShareService shares;
    private final AuthService auth;

    @PostMapping("/{id}/share")
    public ChatDtos.GuildMessageResponse share(@PathVariable String id) {
        return shares.share(auth.getCurrentPlayer().getId(), id);
    }

    @GetMapping("/shared/{token}")
    public Map<String, Object> read(@PathVariable String token) {
        return shares.read(auth.getCurrentPlayer().getId(), token);
    }
}
