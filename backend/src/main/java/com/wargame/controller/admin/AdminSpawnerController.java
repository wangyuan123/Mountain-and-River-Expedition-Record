package com.wargame.controller.admin;

import com.wargame.service.admin.AdminSpawnerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/spawner")
public class AdminSpawnerController {

    private final AdminSpawnerService adminSpawnerService;

    public AdminSpawnerController(AdminSpawnerService adminSpawnerService) {
        this.adminSpawnerService = adminSpawnerService;
    }

    @PostMapping("/spawn-npc")
    public Map<String, Object> spawnNpc(@RequestBody Map<String, Object> body,
                                        Authentication authentication,
                                        HttpServletRequest request) {
        int count = body.containsKey("count") ? ((Number) body.get("count")).intValue() : 1;
        Integer minLevel = body.containsKey("minLevel") && body.get("minLevel") != null ? ((Number) body.get("minLevel")).intValue() : null;
        Integer maxLevel = body.containsKey("maxLevel") && body.get("maxLevel") != null ? ((Number) body.get("maxLevel")).intValue() : null;
        List<Map<String, Object>> created = adminSpawnerService.spawnNpcCities(count, minLevel, maxLevel, authentication.getName(), request);
        return Map.of("success", true, "count", created.size(), "cities", created);
    }

    @PostMapping("/spawn-bots")
    public Map<String, Object> spawnBots(@RequestBody Map<String, Object> body,
                                         Authentication authentication,
                                         HttpServletRequest request) {
        int count = body.containsKey("count") ? ((Number) body.get("count")).intValue() : 1;
        String prefix = body.containsKey("prefix") ? (String) body.get("prefix") : "Bot";
        Integer initialLevel = body.containsKey("initialLevel") && body.get("initialLevel") != null ? ((Number) body.get("initialLevel")).intValue() : 1;
        List<Map<String, Object>> created = adminSpawnerService.spawnMockBots(count, prefix, initialLevel, authentication.getName(), request);
        return Map.of("success", true, "count", created.size(), "bots", created);
    }
}
