package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.WorldViewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/game/world")
public class WorldViewController {
    private final AuthService auth;
    private final WorldViewService views;

    public WorldViewController(AuthService auth, WorldViewService views) {
        this.auth = auth;
        this.views = views;
    }

    /** radius=0 explicitly requests the full map; normal state responses contain a local view. */
    @GetMapping("/view")
    public Map<String, Object> view(@RequestParam int x, @RequestParam int y,
                                    @RequestParam(defaultValue = "3") int radius) {
        return views.getWorld(auth.getCurrentPlayer().getId(), x, y, radius);
    }
}
