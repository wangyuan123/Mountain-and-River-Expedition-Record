package com.wargame.controller;

import com.wargame.model.dto.GameDtos;
import com.wargame.service.ArmyService;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/game/army")
public class ArmyController {

    private final AuthService authService;
    private final ArmyService armyService;
    private final GameStateService gameStateService;

    public ArmyController(AuthService authService, ArmyService armyService, GameStateService gameStateService) {
        this.authService = authService;
        this.armyService = armyService;
        this.gameStateService = gameStateService;
    }

    @PostMapping("/recruit")
    public ResponseEntity<Map<String, Object>> recruit(@RequestBody GameDtos.ArmyRequest request) {
        if (request.unit() == null || request.unit().isBlank()) {
            throw new IllegalArgumentException("兵种不能为空");
        }
        if (request.count() == null || request.count() <= 0) {
            throw new IllegalArgumentException("征召数量必须大于0");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = armyService.recruit(playerId, request.unit(), request.count());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/queue")
    public ResponseEntity<Map<String, Object>> queue() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(Map.of("success", true, "queue", armyService.getProductionQueue(playerId)));
    }

    @PostMapping("/queue/{queueId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelQueue(@PathVariable Long queueId) {
        Long playerId = authService.getCurrentPlayer().getId();
        if (!armyService.cancelProduction(playerId, queueId)) throw new IllegalArgumentException("生产队列不存在");
        return ResponseEntity.ok(Map.of("success", true, "message", "生产已取消，资源与平民已返还", "state", gameStateService.getGameState(playerId)));
    }

    @PostMapping("/queue/{queueId}/speedup")
    public ResponseEntity<Map<String, Object>> speedUp(@PathVariable Long queueId, @RequestBody(required = false) GameDtos.SpeedUpRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        String itemId = (request != null && request.itemId() != null && !request.itemId().isBlank())
                ? request.itemId() : "speedUp10m";
        int count = (request != null && request.count() != null && request.count() > 0) ? request.count() : 1;
        Map<String, Object> result = armyService.useSpeedUp(playerId, itemId, queueId, count);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@RequestBody GameDtos.ArmyRequest request) {
        if (request.unit() == null || request.unit().isBlank()) {
            throw new IllegalArgumentException("兵种不能为空");
        }
        if (request.count() == null || request.count() <= 0) {
            throw new IllegalArgumentException("解散数量必须大于0");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = armyService.dismiss(playerId, request.unit(), request.count());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }
}
