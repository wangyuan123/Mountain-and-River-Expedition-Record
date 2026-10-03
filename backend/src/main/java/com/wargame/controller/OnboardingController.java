package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.quest.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 前进基地行动的状态、偏好和一次性补给接口；玩家身份只取自登录态。 */
@RestController
@RequestMapping("/api/game/onboarding")
@RequiredArgsConstructor
public class OnboardingController {
    private final AuthService auth;
    private final OnboardingService onboarding;
    private final GameStateService game;

    @GetMapping
    public Map<String, Object> status() { return onboarding.status(id()); }

    @PostMapping("/start")
    public Map<String, Object> start() { return onboarding.start(id()); }

    @PostMapping("/pause")
    public Map<String, Object> pause(@RequestBody Map<String, Boolean> body) {
        return onboarding.pause(id(), Boolean.TRUE.equals(body.get("paused")));
    }

    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestBody Map<String, String> body) {
        Long id = id();
        Map<String, Object> result = onboarding.claim(id, body.get("supplyId"));
        result.put("state", game.getGameState(id));
        return result;
    }

    @PostMapping("/skip")
    public Map<String, Object> skip() {
        Long id = id();
        Map<String, Object> result = onboarding.skip(id);
        result.put("state", game.getGameState(id));
        return result;
    }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody Map<String, String> body) { return onboarding.choosePlan(id(), body.get("plan")); }

    /** 确认收尾并返回最新余额，完成奖励沿用一次性发放规则。 */
    @PostMapping("/finish")
    public Map<String, Object> finish() {
        Long id = id();
        Map<String, Object> result = onboarding.choosePlan(id, "explore");
        result.put("state", game.getGameState(id));
        return result;
    }

    @PostMapping("/recover")
    public Map<String, Object> recover() {
        Long id = id();
        Map<String, Object> result = onboarding.recover(id);
        result.put("state", game.getGameState(id));
        return result;
    }

    @PostMapping("/target")
    public Map<String, Object> target(@RequestBody Map<String, Boolean> body) {
        return onboarding.target(id(), Boolean.TRUE.equals(body.get("gather")));
    }

    @PostMapping("/finish-gather")
    public Map<String, Object> finishGather() {
        Long id = id();
        Map<String, Object> result = onboarding.finishGather(id);
        result.put("state", game.getGameState(id));
        return result;
    }

    private Long id() { return auth.getCurrentPlayer().getId(); }
}
