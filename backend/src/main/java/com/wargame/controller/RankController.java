package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.RankService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/game/rank")
public class RankController {

    private final AuthService authService;
    private final RankService rankService;
    private final GameStateService gameStateService;

    public RankController(AuthService authService,
                          RankService rankService,
                          GameStateService gameStateService) {
        this.authService = authService;
        this.rankService = rankService;
        this.gameStateService = gameStateService;
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getRankInfo() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(rankService.getRankInfo(playerId));
    }

    @PostMapping("/promote")
    public ResponseEntity<Map<String, Object>> promoteRank() {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = rankService.promoteRank(playerId);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }
}
