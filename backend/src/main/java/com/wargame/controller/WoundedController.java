package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.WoundedService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/game/army/wounded")
public class WoundedController {
    private final AuthService auth;
    private final WoundedService wounded;
    private final GameStateService state;

    public WoundedController(AuthService auth, WoundedService wounded, GameStateService state) {
        this.auth = auth;
        this.wounded = wounded;
        this.state = state;
    }

    public record HealRequest(Integer count, String currency) {}

    @GetMapping
    public Map<String, Object> camp() {
        return wounded.getCamp(auth.getCurrentPlayer().getId());
    }

    @PostMapping("/{id}/heal")
    public Map<String, Object> heal(@PathVariable Long id, @RequestBody HealRequest request) {
        Long playerId = auth.getCurrentPlayer().getId();
        Map<String, Object> result = wounded.heal(playerId, id, request.count(), request.currency());
        result.put("state", state.getGameState(playerId));
        result.put("camp", wounded.getCamp(playerId));
        return result;
    }
}
