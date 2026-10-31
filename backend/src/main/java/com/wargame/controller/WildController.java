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
        var march = marchService.createDefaultReconDispatch(playerId, "wild", request.wildTileId(), request.abandonNewbieProtection());
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
                request.carryRes(),
                null,
                null,
                request.conquerMode(),
                null,
                request.abandonNewbieProtection()
        );
        marchService.createDispatch(playerId, dispatch);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "征服部队出征成功");
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    /** 仅手动启动已进驻的驻军采集，拒绝旧客户端携带编队直接派兵。 */
    @PostMapping("/gather")
    public ResponseEntity<Map<String, Object>> gather(@RequestBody GameDtos.WildDispatchRequest request) {
        if (request.wildTileId() == null) {
            throw new IllegalArgumentException("野地ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        if (request.army() != null && !request.army().isEmpty()) {
            throw new IllegalArgumentException("派兵采集已取消，请先派遣部队进驻，再手动开启采集");
        }
        Map<String, Object> result = marchService.startWildGather(playerId, request.wildTileId(), request.gatherMode());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/harvest")
    public ResponseEntity<Map<String, Object>> harvest(@RequestBody GameDtos.WildTileRequest request) {
        if (request.wildTileId() == null) {
            throw new IllegalArgumentException("野地ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = marchService.harvestWild(playerId, request.wildTileId());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/recall")
    public ResponseEntity<Map<String, Object>> recall(@RequestBody GameDtos.WildTileRequest request) {
        if (request.wildTileId() == null) {
            throw new IllegalArgumentException("野地ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = marchService.recallWild(playerId, request.wildTileId());
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
        // 校验失败必须走客户端错误分支，避免 HTTP 200 的失败结果被当作成功并覆盖页面状态。
        if (!Boolean.TRUE.equals(result.get("success"))) {
            result.put("error", result.get("message"));
            return ResponseEntity.badRequest().body(result);
        }
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }
}
