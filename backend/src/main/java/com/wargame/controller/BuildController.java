package com.wargame.controller;

import com.wargame.model.dto.GameDtos;
import com.wargame.service.AuthService;
import com.wargame.service.BuildService;
import com.wargame.service.GameStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/game/build")
public class BuildController {

    private final AuthService authService;
    private final BuildService buildService;
    private final GameStateService gameStateService;

    public BuildController(AuthService authService, BuildService buildService, GameStateService gameStateService) {
        this.authService = authService;
        this.buildService = buildService;
        this.gameStateService = gameStateService;
    }

    @PostMapping("/upgrade")
    public ResponseEntity<Map<String, Object>> upgrade(@RequestBody GameDtos.BuildRequest request) {
        if (request.building() == null || request.building().isBlank()) {
            throw new IllegalArgumentException("建筑类型不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        int slot = request.slot() != null ? request.slot() : 0;
        Map<String, Object> result = buildService.upgrade(playerId, request.building(), slot);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody GameDtos.BuildRequest request) {
        if (request.building() == null || request.building().isBlank()) {
            throw new IllegalArgumentException("建筑类型不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        int slot = request.slot() != null ? request.slot() : 0;
        Map<String, Object> result = buildService.cancel(playerId, request.building(), slot);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/speedup")
    public ResponseEntity<Map<String, Object>> speedUp(@RequestBody(required = false) GameDtos.SpeedUpRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        String itemId = (request != null && request.itemId() != null && !request.itemId().isBlank())
                ? request.itemId() : "speedUp10m";
        Long queueId = request != null ? request.queueId() : null;
        int count = (request != null && request.count() != null && request.count() > 0) ? request.count() : 1;
        String building = request != null ? request.building() : null;
        Integer slot = request != null ? request.slot() : null;
        Map<String, Object> result = buildService.useSpeedUp(playerId, itemId, queueId, building, slot, count);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }
}
