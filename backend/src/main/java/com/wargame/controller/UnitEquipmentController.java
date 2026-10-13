package com.wargame.controller;
import com.wargame.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 配装只接受槽位和物品，不提供装备数量接口。 */
@RestController
@RequestMapping("/api/game/unit-equipment")
@RequiredArgsConstructor
public class UnitEquipmentController {
    private final AuthService authService;
    private final UnitEquipmentService equipment;
    private final GameStateService state;
    public record LoadoutRequest(Map<String, String> slots, Long version) {}
    public record ManufactureRequest(String item) {}
    private Map<String, Object> result(Long player, String message) {
        return Map.of("success", true, "message", message, "state", state.getGameState(player));
    }
    @PostMapping("/{unit}/loadout")
    public Map<String, Object> save(@PathVariable String unit, @RequestBody LoadoutRequest request) {
        Long player = authService.getCurrentPlayer().getId(); equipment.save(player, unit, request.slots(), request.version());
        return result(player, "已更新本账号全部该兵种装备");
    }
    @PostMapping("/manufacture")
    public Map<String, Object> manufacture(@RequestBody ManufactureRequest request) {
        Long player = authService.getCurrentPlayer().getId(); equipment.manufacture(player, request.item());
        return result(player, "装备已加入本城制造队列");
    }
    @PostMapping("/orders/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        Long player = authService.getCurrentPlayer().getId(); equipment.cancel(player, id);
        return result(player, "制造已取消，开始前返还全部，开始后返还一半资源");
    }
}
