package com.wargame.controller;

import com.wargame.model.dto.ChatDtos;
import com.wargame.service.AuthService;
import com.wargame.service.GuildChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/game/guild/chat")
public class GuildChatController {

    private final GuildChatService guildChatService;
    private final AuthService authService;

    public GuildChatController(GuildChatService guildChatService, AuthService authService) {
        this.guildChatService = guildChatService;
        this.authService = authService;
    }

    @GetMapping("/history")
    public ResponseEntity<ChatDtos.GuildHistoryResponse> history() {
        return ResponseEntity.ok(new ChatDtos.GuildHistoryResponse(guildChatService.history(playerId())));
    }

    @PostMapping("/send")
    public ResponseEntity<ChatDtos.GuildMessageResponse> send(@RequestBody ChatDtos.SendRequest request) {
        return ResponseEntity.ok(guildChatService.send(playerId(), request != null ? request.content() : ""));
    }

    private Long playerId() {
        return authService.getCurrentPlayer().getId();
    }
}
