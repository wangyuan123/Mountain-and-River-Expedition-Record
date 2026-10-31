package com.wargame.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.AdminPrincipal;
import com.wargame.model.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/** HTTP 入口限流先于 JWT/数据库查询；鉴权成功后追加账号配额，业务服务原有细粒度限额仍保留。 */
@Component
public class RequestRateLimits {
    private static final long MINUTE = 60_000L;
    private final RateLimiter limiter;
    private final ClientIpResolver clientIps;
    private final RequestLimitProperties config;
    private final ObjectMapper json;

    public RequestRateLimits(RateLimiter limiter, ClientIpResolver clientIps, RequestLimitProperties config, ObjectMapper json) {
        this.limiter = limiter;
        this.clientIps = clientIps;
        this.config = config;
        this.json = json;
    }

    /** 包含匿名读取、登录失败和 WebSocket 握手；预检与静态文件不消耗游戏接口预算。 */
    public boolean allowIp(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if ("OPTIONS".equals(request.getMethod())) return true;
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI();
        String ip = clientIps.resolve(request);
        if (path.equals("/ws") || path.startsWith("/ws/"))
            return admit(response, "HTTP:WS:IP:" + ip, config.getWebsocketPerIpPerMinute(), MINUTE);
        if (!path.equals("/api") && !path.startsWith("/api/")) return true;
        return admit(response, "HTTP:ALL:IP:" + ip, config.getIpPerMinute(), MINUTE)
                && (!isWrite(request) || admit(response, "HTTP:WRITE:IP:" + ip, config.getIpWritesPerMinute(), MINUTE))
                && (!path.equals("/api/admin/auth/login") || admit(response, "ADMIN:LOGIN:IP:" + ip, config.getAdminLoginPerIpPerMinute(), MINUTE));
    }

    /** 账号键以服务端身份编号区分玩家与管理员，换 IP、路径、城市或登录令牌均不能重置配额。 */
    public boolean allowAccount(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws IOException {
        if (auth == null || !auth.isAuthenticated() || "OPTIONS".equals(request.getMethod())) return true;
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI();
        if (!path.equals("/api") && !path.startsWith("/api/")) return true;
        String account;
        if (auth.getPrincipal() instanceof AdminPrincipal admin) account = "ADMIN:" + admin.getAdminId();
        else if (auth.getPrincipal() instanceof UserPrincipal player) account = "PLAYER:" + player.getPlayerId();
        else return true;
        return admit(response, "HTTP:ALL:" + account, config.getAccountPerMinute(), MINUTE)
                && (!isWrite(request) || admit(response, "HTTP:WRITE:" + account, config.getAccountWritesPerMinute(), MINUTE));
    }

    /** MySQL 用户名通常不区分大小写；统一键，防止大小写变体各拿一份密码猜测预算。 */
    public void checkAdminLogin(String username) {
        String key = "ADMIN:LOGIN:ACCOUNT:" + username.trim().toLowerCase(Locale.ROOT);
        long window = 15 * MINUTE;
        if (!limiter.allow(key, config.getAdminLoginPerAccountPerQuarterHour(), window))
            throw new AccountException("RATE_LIMITED", "登录尝试过于频繁，请稍后再试", retryAfter(key, window));
    }

    private boolean admit(HttpServletResponse response, String key, int maximum, long window) throws IOException {
        if (limiter.allow(key, maximum, window)) return true;
        long retryAfter = retryAfter(key, window);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getWriter(), Map.of("error", "操作过于频繁，请稍后再试", "code", "RATE_LIMITED", "retryAfter", retryAfter));
        return false;
    }

    private long retryAfter(String key, long window) { return Math.max(1, limiter.retryAfterSeconds(key, window)); }

    private static boolean isWrite(HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod());
    }
}
