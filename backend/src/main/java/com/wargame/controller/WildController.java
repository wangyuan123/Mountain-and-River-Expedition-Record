package com.wargame.controller;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.dto.GameDtos;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.MarchService;
import com.wargame.service.WorldService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/game/wild")
public class WildController {

    private final AuthService authService;
    private final WorldService worldService;
    private final MarchService marchService;
    private final GameStateService gameStateService;

    public WildController(AuthService authService, WorldService worldService,
                          MarchService marchService, GameStateService gameStateService) {
        this.authService = authService;
        this.worldService = worldService;
        this.marchService = marchService;
        this.gameStateService = gameStateService;
    }

    @PostMapping("/scout")
    public ResponseEntity<Map<String, Object>> scout(@RequestBody GameDtos.WildTileRequest request) {
        if (request.wildTileId() == null) {
            throw new IllegalArgumentException("野地ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        // Compatibility endpoint: use the same timed march as the dispatch screen.
        var march = marchService.createDispatch(playerId, new DispatchRequest(
                "wild", request.wildTileId(), "scout", Map.of("scout", 1), null, null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "侦查部队已出征，抵达后生成情报报告");
        result.put("marchId", march.getId());
        result.put("arriveAt", march.getArriveAt());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/conquer")
    public ResponseEntity<Map<String, Object>> conquer(@RequestBody GameDtos.WildDispatchRequest request) {
        if (request.wildTileId() == null) {
            throw new IllegalArgumentException("野地ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        DispatchRequest dispatch = new DispatchRequest(
                "wild",
                request.wildTileId(),
                "conquer",
                request.army(),
                request.commanderId(),
                request.carryRes()
        );
        marchService.createDispatch(playerId, dispatch);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "征服部队出征成功");
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/gather")
    public ResponseEntity<Map<String, Object>> gather(@RequestBody GameDtos.WildDispatchRequest request) {
        if (request.wildTileId() == null) {
            throw new IllegalArgumentException("野地ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        DispatchRequest dispatch = new DispatchRequest(
                "wild_gather",
                request.wildTileId(),
                "gather",
                request.army(),
                request.commanderId(),
                request.carryRes()
        );
        marchService.createDispatch(playerId, dispatch);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "采集部队出发成功");
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/abandon")
    public ResponseEntity<Map<String, Object>> abandon(@RequestBody GameDtos.WildTileRequest request) {
        if (request.wildTileId() == null) {
            throw new IllegalArgumentException("野地ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = worldService.abandonWild(playerId, request.wildTileId());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }
}
