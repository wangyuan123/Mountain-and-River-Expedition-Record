package com.wargame.config;

import com.wargame.util.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * WebSocket 握手拦截器 - 从查询参数中提取并验证 JWT token。
 * <p>
 * 客户端通过 ws://host/ws/game?token=xxx 连接，
 * 拦截器验证 token 后将 playerId 存入 session 属性。
 * <p>
 * 鉴权失败返回 401, 避免浏览器只看到 200 + 立即 close, 无法定位问题.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;
    private final com.wargame.repository.PlayerRepository players;

    public JwtHandshakeInterceptor(JwtUtil jwtUtil, com.wargame.repository.PlayerRepository players) {
        this.jwtUtil = jwtUtil;
        this.players = players;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = extractToken(request.getURI());
        if (token == null) {
            reject(response, "missing token");
            return false;
        }
        if (!jwtUtil.validateToken(token)) {
            reject(response, "invalid or expired token");
            return false;
        }

        Long playerId = jwtUtil.getPlayerIdFromToken(token);
        if (playerId == null) {
            reject(response, "token missing playerId");
            return false;
        }

        var player = players.findById(playerId).orElse(null);
        if (player == null || !player.accountActive() || player.getAuthVersion() != jwtUtil.getAuthVersion(token)) {
            reject(response, "account session expired");
            return false;
        }
        attributes.put("token", token);
        attributes.put("authVersion", player.getAuthVersion());
        attributes.put("playerId", playerId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    /**
     * 拒绝握手并附带 401 状态码 + 原因
     */
    private void reject(ServerHttpResponse response, String reason) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("X-Auth-Reason", reason);
    }

    /**
     * 从 URI 查询参数中提取 token
     */
    private String extractToken(URI uri) {
        String query = uri.getQuery();
        if (query == null || query.isBlank()) return null;

        for (String param : query.split("&")) {
            int idx = param.indexOf('=');
            if (idx > 0 && "token".equals(param.substring(0, idx))) {
                return param.substring(idx + 1);
            }
        }
        return null;
    }
}
