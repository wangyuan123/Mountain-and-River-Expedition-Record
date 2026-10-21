package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.MapFavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 收藏所有者始终取自登录会话，客户端只提交目标和固定坐标。 */
@RestController
@RequestMapping("/api/game/world/map/favorites")
@RequiredArgsConstructor
public class MapFavoriteController {
    private final AuthService auth;
    private final MapFavoriteService favorites;

    @GetMapping
    public List<MapFavoriteService.Favorite> list() {
        return favorites.list(auth.getCurrentPlayer().getId());
    }

    @PostMapping
    public List<MapFavoriteService.Favorite> add(@RequestBody MapFavoriteService.SaveRequest request) {
        return favorites.add(auth.getCurrentPlayer().getId(), request);
    }

    @DeleteMapping
    public List<MapFavoriteService.Favorite> remove(@RequestParam int x, @RequestParam int y) {
        return favorites.remove(auth.getCurrentPlayer().getId(), x, y);
    }
}
