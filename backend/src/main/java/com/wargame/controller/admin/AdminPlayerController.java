package com.wargame.controller.admin;

import com.wargame.service.admin.AdminPlayerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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
                                          @RequestParam(required = false) Integer vipLevel,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "15") int size) {
        return adminPlayerService.getPlayers(query, status, vipLevel, page, size);
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

    @PutMapping("/{id}/vip")
    public Map<String, Object> updateVip(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body,
                                         Authentication authentication,
                                         HttpServletRequest request) {
        adminPlayerService.updatePlayerVip(id, body, authentication.getName(), request);
        return Map.of("success", true, "message", "VIP 特许数据更新成功");
    }

    @PutMapping("/{id}/vip/revoke-rewards")
    public Map<String, Object> revokeUnusedRewards(@PathVariable Long id,
                                                   Authentication authentication,
                                                   HttpServletRequest request) {
        return adminPlayerService.revokePlayerUnusedRewards(id, authentication.getName(), request);
    }

    @PutMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, String> body,
                                             Authentication authentication,
                                             HttpServletRequest request) {
        String newPassword = body != null ? body.get("newPassword") : null;
        String pwd = (newPassword != null && !newPassword.isBlank()) ? newPassword.trim() : "abcd1234";
        adminPlayerService.resetPlayerPassword(id, pwd, authentication.getName(), request);
        return Map.of("success", true, "message", "玩家登录密码已重置为 " + pwd, "plainPassword", pwd);
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
        Long unitId = body.get("id") != null ? ((Number) body.get("id")).longValue() : null;
        Integer citySlot = body.get("citySlot") != null ? ((Number) body.get("citySlot")).intValue() : null;
        String type = (String) body.get("type");
        Integer count = body.get("count") != null ? ((Number) body.get("count")).intValue() : 0;
        adminPlayerService.updatePlayerArmy(id, unitId, citySlot, type, count, authentication.getName(), request);
        return Map.of("success", true, "message", "兵力更新成功");
    }

    @PutMapping("/{id}/army/batch")
    public Map<String, Object> updateArmyBatch(@PathVariable Long id,
                                               @RequestBody Map<String, Object> body,
                                               Authentication authentication,
                                               HttpServletRequest request) {
        Object rawArmies = body.get("armies");
        List<Map<String, Object>> armyList = new ArrayList<>();
        if (rawArmies instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new HashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    armyList.add(copy);
                }
            }
        }
        adminPlayerService.updatePlayerArmiesBatch(id, armyList, authentication.getName(), request);
        return Map.of("success", true, "message", "批量兵力更新成功", "count", armyList.size());
    }

    @PutMapping("/{id}/fortification")
    public Map<String, Object> updateFortification(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body,
                                                   Authentication authentication,
                                                   HttpServletRequest request) {
        String type = (String) body.get("type");
        Integer count = ((Number) body.get("count")).intValue();
        adminPlayerService.updatePlayerFortification(id, type, count, authentication.getName(), request);
        return Map.of("success", true, "message", "城防工事更新成功");
    }

    @PutMapping("/{id}/fortification/batch")
    public Map<String, Object> updateFortificationBatch(@PathVariable Long id,
                                                        @RequestBody Map<String, Object> body,
                                                        Authentication authentication,
                                                        HttpServletRequest request) {
        Object rawForts = body.get("fortifications") != null ? body.get("fortifications") : body.get("forts");
        List<Map<String, Object>> fortList = new ArrayList<>();
        if (rawForts instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new HashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    fortList.add(copy);
                }
            }
        }
        adminPlayerService.updatePlayerFortificationsBatch(id, fortList, authentication.getName(), request);
        return Map.of("success", true, "message", "批量城防更新成功", "count", fortList.size());
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

    @PutMapping("/{id}/buildings/max")
    public Map<String, Object> maxBuildings(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            Authentication authentication,
                                            HttpServletRequest request) {
        Integer targetLevel = body != null && body.get("targetLevel") != null ? ((Number) body.get("targetLevel")).intValue() : 10;
        Integer citySlot = body != null && body.get("citySlot") != null ? ((Number) body.get("citySlot")).intValue() : null;
        int count = adminPlayerService.maxAllPlayerBuildings(id, targetLevel, citySlot, authentication.getName(), request);
        return Map.of("success", true, "message", "已一键将 " + count + " 座建筑等级拉满至 Lv." + targetLevel, "count", count);
    }

    @PutMapping("/{id}/technologies/max")
    public Map<String, Object> maxTechnologies(@PathVariable Long id,
                                               Authentication authentication,
                                               HttpServletRequest request) {
        int count = adminPlayerService.maxAllPlayerTechnologies(id, authentication.getName(), request);
        return Map.of("success", true, "message", "已一键将全部 " + count + " 项科技升至满级", "count", count);
    }

    @PutMapping("/{id}/technologies/reset")
    public Map<String, Object> resetTechnologies(@PathVariable Long id,
                                                 Authentication authentication,
                                                 HttpServletRequest request) {
        int count = adminPlayerService.resetAllPlayerTechnologies(id, authentication.getName(), request);
        return Map.of("success", true, "message", "已一键将 " + count + " 项科技重置为 Lv.0", "count", count);
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

    @PutMapping("/{id}/items/batch")
    public Map<String, Object> updateItemsBatch(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body,
                                                Authentication authentication,
                                                HttpServletRequest request) {
        Object rawItems = body.get("items");
        List<Map<String, Object>> itemList = new ArrayList<>();
        if (rawItems instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new HashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    itemList.add(copy);
                }
            }
        }
        int count = adminPlayerService.updatePlayerItemsBatch(id, itemList, authentication.getName(), request);
        return Map.of("success", true, "message", "批量调整道具成功", "count", count);
    }

    @DeleteMapping("/{id}/item/{itemKey}")
    public Map<String, Object> deleteItem(@PathVariable Long id,
                                          @PathVariable String itemKey,
                                          Authentication authentication,
                                          HttpServletRequest request) {
        adminPlayerService.deletePlayerItem(id, itemKey, authentication.getName(), request);
        return Map.of("success", true, "message", "道具已清空删除");
    }
}
