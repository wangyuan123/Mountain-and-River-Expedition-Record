package com.wargame.controller;

import com.wargame.model.dto.ChatDtos;
import com.wargame.service.AuthService;
import com.wargame.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/game/chat")
public class ChatController {

    private final ChatService chatService;
    private final AuthService authService;

    public ChatController(ChatService chatService, AuthService authService) {
        this.chatService = chatService;
        this.authService = authService;
    }

    @GetMapping("/history")
    public ResponseEntity<ChatDtos.HistoryResponse> history() {
        return ResponseEntity.ok(new ChatDtos.HistoryResponse(chatService.history()));
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody ChatDtos.SendRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        ChatDtos.MessageResponse message = chatService.send(playerId, request.content());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", message);
        return ResponseEntity.ok(result);
    }
}
