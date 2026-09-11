package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.quest.QuestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/game/quest")
public class QuestController {

    private final AuthService authService;
    private final QuestService questService;
    private final GameStateService gameStateService;

    public QuestController(AuthService authService,
                           QuestService questService,
                           GameStateService gameStateService) {
        this.authService = authService;
        this.questService = questService;
        this.gameStateService = gameStateService;
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list() {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chapters", questService.getPlayerQuests(playerId));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/claim")
    public ResponseEntity<Map<String, Object>> claim(@RequestBody Map<String, String> body) {
        String questId = body.get("questId");
        if (questId == null || questId.isBlank()) {
            throw new IllegalArgumentException("questId 不能为空");
        }
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = questService.claim(playerId, questId);
        // 任务奖励已写入 player_items / resources，前端需要立即看到仓库/资源变化，
        // 这里随响应一起返回最新 state，避免前端再发一次 /game/state。
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/guide")
    public ResponseEntity<Map<String, Object>> guide() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(questService.getGuide(playerId));
    }

    @PostMapping("/guide/advance")
    public ResponseEntity<Map<String, Object>> guideAdvance() {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = questService.advanceGuide(playerId);
        // 引导步骤可能派发资源/道具奖励，把最新 state 一起返回，
        // 与 /quest/claim 保持一致,前端无需再发 /game/state。
        if (result != null && Boolean.TRUE.equals(result.get("success"))) {
            result.put("state", gameStateService.getGameState(playerId));
        } else if (result != null && !result.containsKey("success")) {
            // 推进到下一步并成功时,advanceGuide 返回的 nextGuide 没 success 字段,
            // 也要把 state 带上,这样领取奖励后前端能立刻看到仓库/资源变化。
            result.put("state", gameStateService.getGameState(playerId));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/guide/skip")
    public ResponseEntity<Map<String, Object>> guideSkip() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(questService.skipGuide(playerId));
    }
}
