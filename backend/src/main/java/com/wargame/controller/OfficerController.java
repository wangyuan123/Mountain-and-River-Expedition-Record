package com.wargame.controller;

import com.wargame.model.dto.GameDtos;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.OfficerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/game/officer")
public class OfficerController {

    private final AuthService authService;
    private final OfficerService officerService;
    private final GameStateService gameStateService;

    public OfficerController(AuthService authService, OfficerService officerService, GameStateService gameStateService) {
        this.authService = authService;
        this.officerService = officerService;
        this.gameStateService = gameStateService;
    }

    @PostMapping("/refresh-academy")
    public ResponseEntity<Map<String, Object>> refreshAcademy() {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.refreshAcademy(playerId);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/recruit")
    public ResponseEntity<Map<String, Object>> recruit(@RequestBody GameDtos.OfficerIdxRequest request) {
        if (request.officerIdx() == null || request.officerIdx() < 0) {
            throw new IllegalArgumentException("军官索引无效");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.recruit(playerId, request.officerIdx());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/appoint")
    public ResponseEntity<Map<String, Object>> appoint(@RequestBody GameDtos.OfficerAppointRequest request) {
        if (request.officerId() == null) {
            throw new IllegalArgumentException("军官ID不能为空");
        }
        if (request.role() == null || request.role().isBlank()) {
            throw new IllegalArgumentException("职位不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.appoint(playerId, request.officerId(), request.role());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@RequestBody GameDtos.OfficerIdRequest request) {
        if (request.officerId() == null) {
            throw new IllegalArgumentException("军官ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.dismiss(playerId, request.officerId());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/reward")
    public ResponseEntity<Map<String, Object>> reward(@RequestBody GameDtos.OfficerIdRequest request) {
        if (request.officerId() == null) {
            throw new IllegalArgumentException("军官ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.reward(playerId, request.officerId());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/learn-skill")
    public ResponseEntity<Map<String, Object>> learnSkill(@RequestBody GameDtos.OfficerIdRequest request) {
        if (request.officerId() == null) {
            throw new IllegalArgumentException("军官ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.learnSkill(playerId, request.officerId());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/abandon-skill")
    public ResponseEntity<Map<String, Object>> abandonSkill(@RequestBody GameDtos.OfficerAbandonSkillRequest request) {
        if (request.officerId() == null) {
            throw new IllegalArgumentException("军官ID不能为空");
        }
        if (request.skillIdx() == null || request.skillIdx() < 0) {
            throw new IllegalArgumentException("技能索引无效");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.abandonSkill(playerId, request.officerId(), request.skillIdx());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/equip")
    public ResponseEntity<Map<String, Object>> equip(@RequestBody GameDtos.OfficerEquipmentRequest request) {
        if (request.officerId() == null || request.itemId() == null || request.itemId().isBlank()) {
            throw new IllegalArgumentException("军官和装备不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.equip(playerId, request.officerId(), request.itemId());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/unequip")
    public ResponseEntity<Map<String, Object>> unequip(@RequestBody GameDtos.OfficerEquipmentRequest request) {
        if (request.officerId() == null || request.itemId() == null || request.itemId().isBlank()) {
            throw new IllegalArgumentException("军官和装备不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.unequip(playerId, request.officerId(), request.itemId());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    /**
     * 计算军官装备后的总属性（含套装加成），供前端详情面板显示。
     */
    @PostMapping("/attributes")
    public ResponseEntity<Map<String, Object>> attributes(@RequestBody GameDtos.OfficerIdRequest request) {
        if (request.officerId() == null) {
            throw new IllegalArgumentException("军官ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(officerService.computeAttributes(playerId, request.officerId()));
    }

    @PostMapping("/use-exp-book")
    public ResponseEntity<Map<String, Object>> useExpBook(@RequestBody GameDtos.OfficerExpBookRequest request) {
        if (request.officerId() == null) {
            throw new IllegalArgumentException("军官ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        String itemId = request.itemId();
        int count = (request.count() != null && request.count() > 0) ? request.count() : 1;
        Map<String, Object> result = officerService.useExpBook(playerId, request.officerId(), itemId, count);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/level-up")
    public ResponseEntity<Map<String, Object>> levelUp(@RequestBody GameDtos.OfficerLevelUpRequest request) {
        if (request.officerId() == null) {
            throw new IllegalArgumentException("军官ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        boolean all = Boolean.TRUE.equals(request.all());
        Map<String, Object> result = officerService.levelUp(playerId, request.officerId(), all);
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/assign-attr")
    public ResponseEntity<Map<String, Object>> assignAttr(@RequestBody GameDtos.OfficerAssignAttrRequest request) {
        if (request.officerId() == null || request.attr() == null || request.points() == null) {
            throw new IllegalArgumentException("军官ID、属性名和点数不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.assignAttr(playerId, request.officerId(), request.attr(), request.points());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/wash")
    public ResponseEntity<Map<String, Object>> wash(@RequestBody GameDtos.OfficerIdRequest request) {
        if (request.officerId() == null) {
            throw new IllegalArgumentException("军官ID不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = officerService.wash(playerId, request.officerId());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }
}
