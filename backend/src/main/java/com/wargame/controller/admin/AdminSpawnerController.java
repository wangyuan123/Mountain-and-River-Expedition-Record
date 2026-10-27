package com.wargame.controller.admin;

import com.wargame.service.admin.AdminIslandService;
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
    private final AdminIslandService adminIslandService;

    public AdminSpawnerController(AdminSpawnerService adminSpawnerService,
                                  AdminIslandService adminIslandService) {
        this.adminSpawnerService = adminSpawnerService;
        this.adminIslandService = adminIslandService;
    }

    @PostMapping("/spawn-npc")
    public Map<String, Object> spawnNpc(@RequestBody Map<String, Object> body,
                                        Authentication authentication,
                                        HttpServletRequest request) {
        int count = body.containsKey("count") ? ((Number) body.get("count")).intValue() : 1;
        Integer minLevel = body.containsKey("minLevel") && body.get("minLevel") != null ? ((Number) body.get("minLevel")).intValue() : null;
        Integer maxLevel = body.containsKey("maxLevel") && body.get("maxLevel") != null ? ((Number) body.get("maxLevel")).intValue() : null;
        String battlefield = (String) body.getOrDefault("battlefield", "all");
        List<Map<String, Object>> created = adminSpawnerService.spawnNpcCities(count, minLevel, maxLevel, battlefield, authentication.getName(), request);
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
        String battlefield = (String) body.getOrDefault("battlefield", "all");
        List<Map<String, Object>> created = adminSpawnerService.spawnMockBots(count, prefix, initialLevel, battlefield, authentication.getName(), request);
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

    @GetMapping("/wild-stats")
    public Map<String, Object> getWildStats() {
        return adminSpawnerService.getWildStats();
    }

    @PostMapping("/spawn-wilds")
    public Map<String, Object> spawnWilds(@RequestBody Map<String, Object> body,
                                          Authentication authentication,
                                          HttpServletRequest request) {
        String type = (String) body.getOrDefault("type", "ALL_RESOURCES");
        int count = body.containsKey("count") ? ((Number) body.get("count")).intValue() : 10;
        Integer minLevel = body.containsKey("minLevel") && body.get("minLevel") != null ? ((Number) body.get("minLevel")).intValue() : 1;
        Integer maxLevel = body.containsKey("maxLevel") && body.get("maxLevel") != null ? ((Number) body.get("maxLevel")).intValue() : 10;
        boolean clearUnoccupied = Boolean.TRUE.equals(body.get("clearUnoccupied"));
        String battlefield = (String) body.getOrDefault("battlefield", "all");
        String islandCategory = (String) body.getOrDefault("islandCategory", "ALL");

        List<Map<String, Object>> created = adminSpawnerService.spawnWildTiles(type, count, minLevel, maxLevel, clearUnoccupied, battlefield, islandCategory, authentication.getName(), request);
        return Map.of("success", true, "count", created.size(), "wilds", created);
    }

    @GetMapping("/island-stats")
    public Map<String, Object> getIslandStats() {
        return adminIslandService.getIslandStats();
    }

    @PostMapping("/spawn-islands")
    public Map<String, Object> spawnIslands(@RequestBody Map<String, Object> body,
                                           Authentication authentication,
                                           HttpServletRequest request) {
        String category = (String) body.getOrDefault("category", "MICRO");
        int count = body.containsKey("count") ? ((Number) body.get("count")).intValue() : 1;
        boolean seedResources = Boolean.TRUE.equals(body.get("seedResources"));
        List<Map<String, Object>> created = adminIslandService.spawnIslands(category, count, seedResources, authentication.getName(), request);
        return Map.of("success", true, "count", created.size(), "islands", created);
    }

    @PostMapping("/delete-islands")
    public Map<String, Object> deleteIslands(@RequestBody Map<String, Object> body,
                                            Authentication authentication,
                                            HttpServletRequest request) {
        String category = (String) body.get("category");
        Integer count = body.containsKey("count") && body.get("count") != null ? ((Number) body.get("count")).intValue() : null;
        Object rawIds = body.get("islandIds");
        List<Integer> islandIds = null;
        if (rawIds instanceof List<?> list) {
            islandIds = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number n) islandIds.add(n.intValue());
            }
        }
        return adminIslandService.deleteIslands(category, count, islandIds, authentication.getName(), request);
    }

    @PostMapping("/clear-unoccupied-wilds")
    public Map<String, Object> clearUnoccupiedWilds(Authentication authentication,
                                                    HttpServletRequest request) {
        return adminSpawnerService.clearUnoccupiedWilds(authentication.getName(), request);
    }

    @GetMapping("/deletable-wilds")
    public Map<String, Object> getDeletableWilds() {
        List<Map<String, Object>> list = adminSpawnerService.getDeletableWildTiles();
        return Map.of("success", true, "count", list.size(), "list", list);
    }

    @PostMapping("/delete-wilds")
    public Map<String, Object> deleteWilds(@RequestBody Map<String, Object> body,
                                           Authentication authentication,
                                           HttpServletRequest request) {
        Object rawIds = body.get("ids");
        List<Long> ids = new ArrayList<>();
        if (rawIds instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number n) ids.add(n.longValue());
            }
        }
        return adminSpawnerService.deleteWildTiles(ids, authentication.getName(), request);
    }
}
