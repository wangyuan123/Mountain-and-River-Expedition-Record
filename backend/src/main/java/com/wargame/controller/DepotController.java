package com.wargame.controller;

import com.wargame.model.dto.GameDtos;
import com.wargame.service.AuthService;
import com.wargame.service.DepotService;
import com.wargame.service.GameStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仓库控制器 - 处理玩家使用道具。
 * 历史上 depot.js 完全在前端修改 Core.state，没有调用后端 API，
 * 导致刷新页面后所有效果丢失。本控制器提供统一的服务端化入口。
 */
@RestController
@RequestMapping("/api/game/depot")
public class DepotController {

    private final AuthService authService;
    private final DepotService depotService;
    private final GameStateService gameStateService;

    public DepotController(AuthService authService,
                           DepotService depotService,
                           GameStateService gameStateService) {
        this.authService = authService;
        this.depotService = depotService;
        this.gameStateService = gameStateService;
    }

    @PostMapping("/use")
    public ResponseEntity<Map<String, Object>> useItem(@RequestBody GameDtos.DepotUseRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = depotService.useItem(
                playerId,
                request.itemId(),
                request.officerId(),
                request.newName()
        );
        // 成功后下发最新 state，让前端无需二次 GET
        if (Boolean.TRUE.equals(result.get("success"))) {
            result.put("state", gameStateService.getGameState(playerId));
        }
        return ResponseEntity.ok(result);
    }
}
