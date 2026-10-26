package com.wargame.controller;

import com.wargame.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 整套与培养操作只接受选择，不接受客户端属性、价格或材料数量。 */
@RestController
@RequestMapping("/api/game/officer-equipment")
@RequiredArgsConstructor
public class OfficerEquipmentController {
    private final AuthService auth;
    private final EquipmentService equipment;
    private final OfficerEquipmentProgressService progress;
    private final GameStateService state;
    public record SetRequest(Long officerId, String setKey) {}
    public record PrepareRequest(Long officerId, String slot, Integer expectedLevel) {}
    public record ClaimRequest(String task) {}
    public record LoadoutRequest(Long officerId, int index) {}
    private Map<String, Object> response(Long player, Map<String, Object> result) {
        var response = new LinkedHashMap<>(result); response.put("state", state.getGameState(player)); return response;
    }
    @PostMapping("/equip-set")
    public Map<String, Object> equipSet(@RequestBody SetRequest r) {
        Long p = auth.getCurrentPlayer().getId(); return response(p, equipment.equipSet(p, r.officerId(), r.setKey()));
    }
    @PostMapping("/unequip-all")
    public Map<String, Object> unequipAll(@RequestBody SetRequest r) {
        Long p = auth.getCurrentPlayer().getId(); return response(p, equipment.unequip(p, r.officerId(), "all"));
    }
    @PostMapping("/prepare")
    public Map<String, Object> prepare(@RequestBody PrepareRequest r) {
        if (r.expectedLevel() == null) throw new IllegalArgumentException("缺少当前整备等级");
        Long p = auth.getCurrentPlayer().getId(); return response(p, equipment.prepare(p, r.officerId(), r.slot(), r.expectedLevel()));
    }
    @PostMapping("/save-loadout")
    public Map<String, Object> saveLoadout(@RequestBody LoadoutRequest r) {
        Long p = auth.getCurrentPlayer().getId(); return response(p, progress.saveLoadout(p, r.index(), r.officerId()));
    }
    @PostMapping("/equip-loadout")
    public Map<String, Object> equipLoadout(@RequestBody LoadoutRequest r) {
        Long p = auth.getCurrentPlayer().getId(); return response(p, equipment.equipLoadout(p, r.officerId(), progress.loadout(p, r.index())));
    }
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        Long p = auth.getCurrentPlayer().getId(); return response(p, Map.of("success", true, "message", "军备任务已刷新"));
    }
    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestBody ClaimRequest r) {
        Long p = auth.getCurrentPlayer().getId(); return response(p, progress.claim(p, r.task()));
    }
}
