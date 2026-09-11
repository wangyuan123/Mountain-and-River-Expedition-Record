package com.wargame.controller;

import com.wargame.model.dto.GameDtos;
import com.wargame.service.AuthService;
import com.wargame.service.FortService;
import com.wargame.service.GameStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/game/fort")
public class FortController {

    private final AuthService authService;
    private final FortService fortService;
    private final GameStateService gameStateService;

    public FortController(AuthService authService, FortService fortService, GameStateService gameStateService) {
        this.authService = authService;
        this.fortService = fortService;
        this.gameStateService = gameStateService;
    }

    @PostMapping("/build")
    public ResponseEntity<Map<String, Object>> build(@RequestBody GameDtos.FortRequest request) {
        if (request.fort() == null || request.fort().isBlank()) {
            throw new IllegalArgumentException("城防类型不能为空");
        }
        if (request.count() == null || request.count() <= 0) {
            throw new IllegalArgumentException("修筑数量必须大于0");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = fortService.build(playerId, request.fort(), request.count());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/dismantle")
    public ResponseEntity<Map<String, Object>> dismantle(@RequestBody GameDtos.FortRequest request) {
        if (request.fort() == null || request.fort().isBlank()) {
            throw new IllegalArgumentException("城防类型不能为空");
        }
        if (request.count() == null || request.count() <= 0) {
            throw new IllegalArgumentException("拆除数量必须大于0");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = fortService.dismantle(playerId, request.fort(), request.count());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }
}
