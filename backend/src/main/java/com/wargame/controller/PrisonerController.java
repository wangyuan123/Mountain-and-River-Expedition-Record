package com.wargame.controller;

import com.wargame.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 只操作JWT玩家和X-City-Id指定的所属城市；价格、科技与容量均由服务端确定。 */
@RestController
@RequestMapping("/api/game/prisoners")
@RequiredArgsConstructor
public class PrisonerController {
    private final AuthService auth;
    private final PrisonerService prisoners;
    private final GameStateService state;

    public record Request(String unit, Integer count, String operationKey) {}

    @GetMapping
    public Map<String, Object> pool() { return prisoners.getPool(auth.getCurrentPlayer().getId()); }

    @PostMapping("/recruit")
    public Map<String, Object> recruit(@RequestBody Request request, @RequestHeader(value = "X-City-Id", required = false) Long cityId) { return operate("recruit", request, cityId); }

    @PostMapping("/release")
    public Map<String, Object> release(@RequestBody Request request, @RequestHeader(value = "X-City-Id", required = false) Long cityId) { return operate("release", request, cityId); }

    private Map<String, Object> operate(String action, Request request, Long cityId) {
        Long id = auth.getCurrentPlayer().getId();
        Map<String, Object> result = cityId == null
                ? prisoners.operate(id, action, request.unit(), request.count(), request.operationKey())
                : prisoners.operateInCity(id, cityId, action, request.unit(), request.count(), request.operationKey());
        result.put("state", state.getGameState(id)); result.put("pool", prisoners.getPool(id));
        return result;
    }
}
