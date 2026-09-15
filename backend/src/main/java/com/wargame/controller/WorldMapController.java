package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.WorldMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/game/world/map")
@RequiredArgsConstructor
public class WorldMapController {
    private final AuthService auth;
    private final WorldMapService maps;
    private final com.wargame.service.WorldTerrainService terrain;

    @GetMapping("/terrain")
    public Map<String,Object> terrain() { auth.getCurrentPlayer(); return terrain.descriptor(); }

    @GetMapping("/chunk")
    public Map<String, Object> chunk(@RequestParam int cx, @RequestParam int cy) {
        return maps.chunk(auth.getCurrentPlayer().getId(), cx, cy);
    }
    @GetMapping("/target")
    public Map<String, Object> target(@RequestParam String kind, @RequestParam Long id) {
        return maps.target(auth.getCurrentPlayer().getId(), kind, id);
    }
}
