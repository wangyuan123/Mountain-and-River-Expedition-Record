package com.wargame.controller;

import com.wargame.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@RestController
@RequestMapping("/api/game/cities")
public class CityController {
    private final AuthService auth;
    private final CityService cities;
    private final CityScope scope;
    private final GameStateService state;
    public CityController(AuthService auth, CityService cities, CityScope scope, GameStateService state) {
        this.auth = auth; this.cities = cities; this.scope = scope; this.state = state;
    }
    public record Selection(Long cityId) {}
    public record Founding(Long wildId, String name, Integer x, Integer y) {}
    @GetMapping("/site") public Map<String,Object> site(@RequestParam int x,@RequestParam int y) { return cities.site(auth.getCurrentPlayer().getId(),x,y); }
    @GetMapping public Map<String, Object> list() { return cities.overview(auth.getCurrentPlayer().getId()); }
    @PostMapping("/switch") @Transactional
    public Map<String, Object> select(@RequestBody Selection request) {
        Long id = auth.getCurrentPlayer().getId();
        var city = cities.select(id, request.cityId());
        try (var ignored = scope.enter(city)) { return Map.of("success", true, "state", state.getGameState(id)); }
    }
    @PostMapping @Transactional
    public Map<String, Object> found(@RequestBody Founding request) {
        Long id = auth.getCurrentPlayer().getId();
        var city = cities.foundAt(id, request.wildId(), request.x(), request.y(), request.name());
        return Map.of("success", true, "cityId", city.getId(), "readyAt", city.getReadyAt(), "message", "新城已开始建设，30分钟后可进入", "state", state.getGameState(id));
    }
}
