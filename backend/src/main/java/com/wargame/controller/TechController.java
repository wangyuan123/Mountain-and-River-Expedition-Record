package com.wargame.controller;

import com.wargame.model.dto.GameDtos;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.TechService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/game/tech")
public class TechController {

    private final AuthService authService;
    private final TechService techService;
    private final GameStateService gameStateService;

    public TechController(AuthService authService, TechService techService, GameStateService gameStateService) {
        this.authService = authService;
        this.techService = techService;
        this.gameStateService = gameStateService;
    }

    @PostMapping("/upgrade")
    public ResponseEntity<Map<String, Object>> upgrade(@RequestBody GameDtos.TechRequest request) {
        if (request.tech() == null || request.tech().isBlank()) {
            throw new IllegalArgumentException("科技类型不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = techService.upgrade(playerId, request.tech());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }
}
