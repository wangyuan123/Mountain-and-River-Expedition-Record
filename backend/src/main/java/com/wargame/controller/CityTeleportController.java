package com.wargame.controller;

import com.wargame.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigDecimal;

/** 传送使用请求固定的 X-City-Id；客户端不能借请求正文指定他人城市。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/game/cities/teleport")
public class CityTeleportController {
    private final AuthService auth;
    private final CityTeleportService teleport;
    private final GameStateService state;
    public record Request(String itemId, String battlefield, BigDecimal x, BigDecimal y) {}

    private Integer coordinate(BigDecimal value) {
        if (value == null) return null;
        try { return value.intValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("传送坐标必须为地图范围内的整数"); }
    }

    @GetMapping("/site")
    public Map<String, Object> site(@RequestParam String battlefield, @RequestParam int x, @RequestParam int y) {
        return teleport.preview(auth.getCurrentPlayer().getId(), battlefield, x, y);
    }

    /** 仅返回当前请求城市的检查结果，不授予或缓存传送资格。 */
    @GetMapping("/readiness")
    public Map<String, Object> readiness() {
        return teleport.readiness(auth.getCurrentPlayer().getId());
    }

    @PostMapping
    @Transactional
    public Map<String, Object> teleport(@RequestBody Request request) {
        Long id = auth.getCurrentPlayer().getId();
        Map<String, Object> result = new LinkedHashMap<>(teleport.teleport(id, request.itemId(), request.battlefield(), coordinate(request.x()), coordinate(request.y())));
        result.put("state", state.getGameState(id));
        return result;
    }
}
