package com.wargame.controller;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.dto.GameDtos;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.MarchService;
import com.wargame.service.WorldService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/game/world")
public class WorldController {

    private final AuthService authService;
    private final WorldService worldService;
    private final MarchService marchService;
    private final GameStateService gameStateService;

    public WorldController(AuthService authService, WorldService worldService,
                           MarchService marchService, GameStateService gameStateService) {
        this.authService = authService;
        this.worldService = worldService;
        this.marchService = marchService;
        this.gameStateService = gameStateService;
    }

    @PostMapping("/move")
    public ResponseEntity<Map<String, Object>> move(@RequestBody GameDtos.MoveRequest request) {
        if (request.direction() == null || request.direction().isBlank()) {
            throw new IllegalArgumentException("移动方向不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = worldService.move(playerId, request.direction());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan() {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = worldService.scan(playerId);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/dispatch")
    public ResponseEntity<Map<String, Object>> dispatch(@RequestBody DispatchRequest request) {
        if (request.targetKind() == null || request.targetKind().isBlank()) {
            throw new IllegalArgumentException("目标类型不能为空");
        }
        if (request.targetId() == null) {
            throw new IllegalArgumentException("目标ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        marchService.createDispatch(playerId, request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "部队出征成功");
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/cancel-march")
    public ResponseEntity<Map<String, Object>> cancelMarch(@RequestBody GameDtos.CancelMarchRequest request) {
        if (request.marchId() == null) {
            throw new IllegalArgumentException("行军ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        marchService.cancelMarch(playerId, request.marchId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "行军已取消");
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/declare-war")
    public ResponseEntity<Map<String, Object>> declareWar(@RequestBody GameDtos.DeclareWarRequest request) {
        if (request.targetCityId() == null) {
            throw new IllegalArgumentException("目标城市ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = worldService.declareWar(playerId, request.targetCityId());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/nearby")
    public ResponseEntity<Map<String, Object>> nearby() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(worldService.getNearbyCities(playerId));
    }

    @GetMapping("/coordinate")
    public ResponseEntity<Map<String, Object>> coordinate(
            @RequestParam int x, @RequestParam int y) {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(worldService.findAtCoordinate(playerId, x, y));
    }

    @GetMapping("/wild-tiles")
    public ResponseEntity<List<Map<String, Object>>> wildTiles() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(worldService.getOwnedWildTiles(playerId));
    }

}
