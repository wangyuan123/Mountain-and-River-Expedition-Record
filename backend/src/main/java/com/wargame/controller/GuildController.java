package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.GuildService;
import com.wargame.model.dto.GameDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/game/guild")
public class GuildController {
    private final GuildService guildService;
    private final AuthService authService;

    public GuildController(GuildService guildService, AuthService authService) {
        this.guildService = guildService;
        this.authService = authService;
    }

    @GetMapping("/mine")
    public ResponseEntity<Map<String, Object>> mine() { return ResponseEntity.ok(guildService.getMyGuild(playerId())); }
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> list() { return ResponseEntity.ok(guildService.browse()); }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) { return ResponseEntity.ok(guildService.create(playerId(), (String) body.get("name"))); }
    @PostMapping("/{guildId}/apply")
    public ResponseEntity<Map<String, Object>> apply(@PathVariable Long guildId) { return ResponseEntity.ok(guildService.apply(playerId(), guildId)); }
    @PostMapping("/applications/{id}/review")
    public ResponseEntity<Map<String, Object>> review(@PathVariable Long id, @RequestBody Map<String, Object> body) { return ResponseEntity.ok(guildService.review(playerId(), id, Boolean.TRUE.equals(body.get("approved")))); }
    @PostMapping("/notice")
    public ResponseEntity<Map<String, Object>> notice(@RequestBody Map<String, Object> body) { return ResponseEntity.ok(guildService.updateNotice(playerId(), (String) body.get("notice"))); }
    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> settings(@RequestBody GameDtos.GuildSettingsRequest request) { return ResponseEntity.ok(guildService.updateSettings(playerId(), request.name(), request.icon())); }
    @PostMapping("/members/{targetPlayerId}/role")
    public ResponseEntity<Map<String, Object>> role(@PathVariable Long targetPlayerId, @RequestBody GameDtos.GuildRoleRequest request) { return ResponseEntity.ok(guildService.updateRole(playerId(), targetPlayerId, request.role())); }
    @PostMapping("/members/{targetPlayerId}/remove")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable Long targetPlayerId) { return ResponseEntity.ok(guildService.removeMember(playerId(), targetPlayerId)); }
    @PostMapping("/leave")
    public ResponseEntity<Map<String, Object>> leave() { return ResponseEntity.ok(guildService.leave(playerId())); }

    private Long playerId() { return authService.getCurrentPlayer().getId(); }
}
