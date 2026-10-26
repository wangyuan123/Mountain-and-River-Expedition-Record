package com.wargame.controller.admin;

import com.wargame.service.bot.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 沿用 /api/admin 的管理员权限；真实账号转换须独立显式确认。 */
@RestController
@RequestMapping("/api/admin/bots")
@RequiredArgsConstructor
public class AdminBotController {
    private final BotStore store;
    private final BotService bots;
    private final BotScheduler scheduler;

    public record ConfigureRequest(BotStore.Settings settings, boolean convertToBot) {}

    @GetMapping
    public List<Map<String, Object>> list() { return store.list(); }

    @PutMapping("/{playerId}")
    public BotStore.Profile configure(@PathVariable long playerId, @RequestBody ConfigureRequest request,
                                      Authentication authentication, HttpServletRequest http) {
        if (request.settings() == null) throw new IllegalArgumentException("settings 不能为空");
        return bots.configure(playerId, request.settings(), request.convertToBot(), authentication.getName(), http);
    }

    @PostMapping("/{playerId}/step")
    public BotService.Result step(@PathVariable long playerId) { return scheduler.step(playerId, true); }

    @GetMapping("/{playerId}/logs")
    public List<Map<String, Object>> logs(@PathVariable long playerId, @RequestParam(defaultValue = "30") int limit) { return store.logs(playerId, limit); }
}
