package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.LeaderboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/game/leaderboard")
public class LeaderboardController {
    private final AuthService authService;
    private final LeaderboardService leaderboards;

    public LeaderboardController(AuthService authService, LeaderboardService leaderboards) {
        this.authService = authService;
        this.leaderboards = leaderboards;
    }

    /** 名次按当前大区统计，当前玩家身份只取自认证上下文。 */
    @GetMapping
    public Map<String, Object> get(@RequestParam(defaultValue = "players") String type,
                                   @RequestParam(defaultValue = "prestige") String metric,
                                   @RequestParam(defaultValue = "1") int page) {
        return leaderboards.get(authService.getCurrentPlayer().getId(), type, metric, page);
    }
}
