package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.RadarService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/game/world/radar")
@RequiredArgsConstructor
public class RadarController {
    private final AuthService auth;
    private final RadarService radar;

    public record Setting(Long cityId, Boolean enabled) {}

    @GetMapping
    public Map<String, Object> view(@RequestParam(defaultValue = "0") long beforeId) {
        return radar.view(auth.getCurrentPlayer().getId(), beforeId);
    }

    @PostMapping("/setting")
    public Map<String, Object> setting(@RequestBody Setting setting) {
        if (setting.cityId() == null || setting.enabled() == null) throw new IllegalArgumentException("请选择城市及雷达状态");
        return radar.setEnabled(auth.getCurrentPlayer().getId(), setting.cityId(), setting.enabled());
    }

    @PostMapping("/{contactId}/read")
    public Map<String, Object> read(@PathVariable Long contactId) {
        return radar.read(auth.getCurrentPlayer().getId(), contactId);
    }
}
