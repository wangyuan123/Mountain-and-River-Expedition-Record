package com.wargame.controller.admin;

import com.wargame.service.admin.AdminPlayerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/players")
public class AdminPlayerController {

    private final AdminPlayerService adminPlayerService;

    public AdminPlayerController(AdminPlayerService adminPlayerService) {
        this.adminPlayerService = adminPlayerService;
    }

    @GetMapping
    public Page<Map<String, Object>> list(@RequestParam(required = false) String query,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "15") int size) {
        return adminPlayerService.getPlayers(query, status, page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return adminPlayerService.getPlayerDetail(id);
    }

    @PostMapping("/{id}/ban")
    public Map<String, Object> ban(@PathVariable Long id,
                                   @RequestBody(required = false) Map<String, Object> body,
                                   Authentication authentication,
                                   HttpServletRequest request) {
        String reason = body != null && body.containsKey("reason") ? (String) body.get("reason") : "违规封禁";
        Long durationHours = null;
        if (body != null && body.containsKey("durationHours") && body.get("durationHours") != null) {
            durationHours = ((Number) body.get("durationHours")).longValue();
        }
        adminPlayerService.banPlayer(id, reason, durationHours, authentication.getName(), request);
        return Map.of("success", true, "message", "封号成功并已强制剔除下线");
    }

    @PostMapping("/{id}/unban")
    public Map<String, Object> unban(@PathVariable Long id,
                                     Authentication authentication,
                                     HttpServletRequest request) {
        adminPlayerService.unbanPlayer(id, authentication.getName(), request);
        return Map.of("success", true, "message", "解封成功");
    }

    @PutMapping("/{id}/basic")
    public Map<String, Object> updateBasic(@PathVariable Long id,
                                           @RequestBody Map<String, Object> body,
                                           Authentication authentication,
                                           HttpServletRequest request) {
        adminPlayerService.updatePlayerBasic(id, body, authentication.getName(), request);
        return Map.of("success", true, "message", "基础属性更新成功");
    }

    @PutMapping("/{id}/resources")
    public Map<String, Object> updateResources(@PathVariable Long id,
                                               @RequestBody Map<String, Integer> body,
                                               Authentication authentication,
                                               HttpServletRequest request) {
        adminPlayerService.updatePlayerResources(id, body, authentication.getName(), request);
        return Map.of("success", true, "message", "资源更新成功");
    }

    @PutMapping("/{id}/army")
    public Map<String, Object> updateArmy(@PathVariable Long id,
                                          @RequestBody Map<String, Object> body,
                                          Authentication authentication,
                                          HttpServletRequest request) {
        String type = (String) body.get("type");
        Integer count = ((Number) body.get("count")).intValue();
        adminPlayerService.updatePlayerArmy(id, type, count, authentication.getName(), request);
        return Map.of("success", true, "message", "兵力更新成功");
    }

    @PutMapping("/{id}/building")
    public Map<String, Object> updateBuilding(@PathVariable Long id,
                                              @RequestBody Map<String, Object> body,
                                              Authentication authentication,
                                              HttpServletRequest request) {
        Long buildingId = ((Number) body.get("buildingId")).longValue();
        Integer level = ((Number) body.get("level")).intValue();
        adminPlayerService.updatePlayerBuilding(id, buildingId, level, authentication.getName(), request);
        return Map.of("success", true, "message", "建筑等级更新成功");
    }

    @PutMapping("/{id}/technology")
    public Map<String, Object> updateTechnology(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body,
                                                Authentication authentication,
                                                HttpServletRequest request) {
        String techType = (String) body.get("techType");
        Integer level = ((Number) body.get("level")).intValue();
        adminPlayerService.updatePlayerTechnology(id, techType, level, authentication.getName(), request);
        return Map.of("success", true, "message", "科技等级更新成功");
    }

    @PutMapping("/{id}/item")
    public Map<String, Object> updateItem(@PathVariable Long id,
                                          @RequestBody Map<String, Object> body,
                                          Authentication authentication,
                                          HttpServletRequest request) {
        String itemKey = (String) body.get("itemKey");
        Integer count = ((Number) body.get("count")).intValue();
        adminPlayerService.updatePlayerItem(id, itemKey, count, authentication.getName(), request);
        return Map.of("success", true, "message", "道具数量更新成功");
    }
}
