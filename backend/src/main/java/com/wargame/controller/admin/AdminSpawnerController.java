package com.wargame.controller.admin;

import com.wargame.service.admin.AdminSpawnerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
        String prefix = body.containsKey("prefix") && body.get("prefix") != null && !((String) body.get("prefix")).isBlank()
                ? (String) body.get("prefix")
                : "远征统帅";
        Integer initialLevel = body.containsKey("initialLevel") && body.get("initialLevel") != null ? ((Number) body.get("initialLevel")).intValue() : 1;
        List<Map<String, Object>> created = adminSpawnerService.spawnMockBots(count, prefix, initialLevel, authentication.getName(), request);
        return Map.of("success", true, "count", created.size(), "bots", created);
    }

    @GetMapping("/deletable-npcs")
    public Map<String, Object> getDeletableNpcs() {
        List<Map<String, Object>> list = adminSpawnerService.getDeletableNpcCities();
        return Map.of("success", true, "count", list.size(), "list", list);
    }

    @PostMapping("/delete-npcs")
    public Map<String, Object> deleteNpcs(@RequestBody Map<String, Object> body,
                                          Authentication authentication,
                                          HttpServletRequest request) {
        Object rawIds = body.get("ids");
        List<Long> ids = new ArrayList<>();
        if (rawIds instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number n) ids.add(n.longValue());
            }
        }
        return adminSpawnerService.deleteNpcCities(ids, authentication.getName(), request);
    }

    @GetMapping("/deletable-bots")
    public Map<String, Object> getDeletableBots() {
        List<Map<String, Object>> list = adminSpawnerService.getDeletableBots();
        return Map.of("success", true, "count", list.size(), "list", list);
    }

    @PostMapping("/delete-bots")
    public Map<String, Object> deleteBots(@RequestBody Map<String, Object> body,
                                          Authentication authentication,
                                          HttpServletRequest request) {
        Object rawIds = body.get("ids");
        List<Long> ids = new ArrayList<>();
        if (rawIds instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number n) ids.add(n.longValue());
            }
        }
        return adminSpawnerService.deleteBots(ids, authentication.getName(), request);
    }
}
