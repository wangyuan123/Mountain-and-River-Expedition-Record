package com.wargame.controller;

import com.wargame.model.dto.PrivateChatDtos;
import com.wargame.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/game/chat/private")
public class PrivateChatController {
    private final PrivateChatService chat;
    private final AuthService auth;
    public PrivateChatController(PrivateChatService chat, AuthService auth) { this.chat = chat; this.auth = auth; }
    private Long me() { return auth.getCurrentPlayer().getId(); }
    @DeleteMapping("/{peerId}")
    public Map<String, Object> delete(@PathVariable Long peerId) {
        chat.deleteConversation(me(), peerId); return Map.of("success", true);
    }
    @GetMapping("/player")
    public PrivateChatDtos.Peer player(@RequestParam String username) { return chat.resolve(me(), username); }
    @PostMapping("/{peerId}/open")
    public PrivateChatDtos.Peer open(@PathVariable Long peerId) { return chat.open(me(), peerId); }
    @GetMapping("/conversations")
    public List<PrivateChatDtos.Conversation> conversations() { return chat.conversations(me()); }
    @GetMapping("/{peerId}/history")
    public Map<String, Object> history(@PathVariable Long peerId) { return Map.of("messages", chat.history(me(), peerId)); }
    @PostMapping("/{peerId}/read")
    public Map<String, Object> read(@PathVariable Long peerId, @RequestBody PrivateChatDtos.ReadRequest request) {
        chat.read(me(), peerId, request.throughId()); return Map.of("success", true);
    }
    @PostMapping("/send")
    public PrivateChatDtos.Message send(@RequestBody PrivateChatDtos.SendRequest request) {
        return chat.send(me(), request.recipientId(), request.content());
    }
}
