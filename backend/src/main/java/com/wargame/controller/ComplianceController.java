package com.wargame.controller;

import com.wargame.model.compliance.ComplianceRecords.SupportRequest;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.compliance.AntiAddictionService;
import com.wargame.security.GameAccessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 账号服务在休息时仍可用；它们不返回地图、战报或其他游戏数据。 */
@RestController
@RequiredArgsConstructor
public class ComplianceController {
    private final AuthService auth;
    private final AntiAddictionService protection;
    private final GameStateService state;
    public record VerifyRequest(String proof) {}
    public record SessionRequest(String sessionSecret) {}
    public record RestrictionRequest(Long playerId, Integer dailyLimitSeconds, Integer endMinute, Boolean paused, Boolean chatAllowed) {}
    public record HelpRequest(String kind, String requestId) {}

    @GetMapping({"/api/anti-addiction/status", "/api/identity/status"})
    public Map<String, Object> status(HttpServletRequest request) {
        return protection.status(auth.getCurrentPlayer().getId(), request.getHeader("X-Play-Session"));
    }
    @PostMapping("/api/identity/verify")
    public Map<String, Object> verify(@RequestBody VerifyRequest input, HttpServletRequest request) {
        // 测试身份即使误启用也不能由远程 HTTP 请求兑换；不信任 X-Forwarded-For。
        if (protection.fixtures() && !List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()))
            throw new GameAccessException("LOCAL_FIXTURE_ONLY", "测试身份仅限本机使用");
        return protection.verify(auth.getCurrentPlayer(), input.proof());
    }
    @PostMapping("/api/play-sessions")
    public Map<String, Object> start(@RequestBody SessionRequest input) {
        var player = auth.getCurrentPlayer();
        var result = protection.start(player, input.sessionSecret());
        // 新账号只在实名及时间许可均通过后进入共享世界。
        if (!player.isGameInitialized()) state.initializeNewPlayer(player.getId());
        protection.requireAccess(player, input.sessionSecret(), "/api/game/state");
        return result;
    }
    @PostMapping("/api/play-sessions/heartbeat")
    public Map<String, Object> heartbeat(HttpServletRequest request) { return protection.renew(auth.getCurrentPlayer(), request.getHeader("X-Play-Session")); }
    @PostMapping("/api/play-sessions/end")
    public Map<String, Object> end(HttpServletRequest request) {
        protection.end(auth.getCurrentPlayer(), request.getHeader("X-Play-Session")); return Map.of("success", true);
    }
    @GetMapping("/api/guardian/children")
    public List<Map<String, Object>> children() { return protection.children(auth.getCurrentPlayer().getId()); }
    @PostMapping("/api/guardian/restrictions")
    public Map<String, Object> restrict(@RequestBody RestrictionRequest input) {
        if (input.playerId() == null || input.dailyLimitSeconds() == null || input.endMinute() == null || input.paused() == null || input.chatAllowed() == null)
            throw new IllegalArgumentException("请填写完整的监护设置");
        protection.restrict(auth.getCurrentPlayer().getId(), input.playerId(), input.dailyLimitSeconds(), input.endMinute(), input.paused(), input.chatAllowed());
        return Map.of("success", true);
    }
    @PostMapping("/api/protection/requests")
    public Map<String, Object> help(@RequestBody HelpRequest input) { return protection.requestHelp(auth.getCurrentPlayer().getId(), input.kind(), input.requestId()); }
    @GetMapping("/api/protection/requests")
    public List<SupportRequest> requests() { return protection.requests(auth.getCurrentPlayer().getId()); }
}
