package com.wargame.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏 WebSocket 处理器。
 * <p>
 * 维护 playerId -> sessions 的映射，支持同一玩家多标签页连接。
 * 处理客户端心跳（ping/pong）。
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

    /** playerId -> 该玩家的所有活跃 WebSocket 会话 */
    private final Map<Long, Set<WebSocketSession>> playerSessions = new ConcurrentHashMap<>();

    private final Map<String, Long> lastHeartbeats = new ConcurrentHashMap<>();
    private static final long PRESENCE_TIMEOUT_MS = 90_000;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.repository.PlayerRepository players;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.util.JwtUtil jwt;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.service.compliance.AntiAddictionService protection;

    /** 逐次发送与心跳均检查持久化版本，多实例不依赖本机撤销表。 */
    private boolean validSession(WebSocketSession session) {
        if (players == null) return true; // 独立处理器单元测试没有 Spring 容器。
        Object token = session.getAttributes().get("token");
        if (!(token instanceof String value) || !jwt.validateToken(value)) return false;
        var player = players.findById(getPlayerId(session)).orElse(null);
        if (player == null || !player.accountActive() || player.getAuthVersion() != jwt.getAuthVersion(value)) return false;
        try {
            protection.requireAccess(player, (String) session.getAttributes().get("playSession"), "/ws/game");
            return true;
        } catch (com.wargame.security.GameAccessException e) {
            session.getAttributes().put("gameAccessRevoked", true);
            return false;
        } catch (RuntimeException e) {
            // 许可存储不可用时停止推送，不把基础设施故障当成允许访问。
            session.getAttributes().put("gameAccessRevoked", true);
            return false;
        }
    }

    public void disconnectPlayer(Long playerId) {
        for (WebSocketSession session : java.util.List.copyOf(getSessions(playerId))) closeExpired(session);
    }

    private void closeExpired(WebSocketSession session) {
        try { session.close(Boolean.TRUE.equals(session.getAttributes().get("gameAccessRevoked"))
                ? new CloseStatus(4003, "game access revoked") : new CloseStatus(4001, "account session expired")); }
        catch (IOException e) { log.debug("关闭失效账号连接失败", e); }
        afterConnectionClosed(session, CloseStatus.POLICY_VIOLATION);
    }

    /** 其他实例受理注销后，最迟下一次校验关闭本实例空闲连接。 */
    public void closeInvalidSessions() {
        for (var sessions : playerSessions.values()) {
            for (var session : java.util.List.copyOf(sessions)) {
                if (!validSession(session)) closeExpired(session);
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long playerId = getPlayerId(session);
        if (playerId == null) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException e) {
                log.warn("关闭无 playerId 的 WebSocket 会话失败", e);
            }
            return;
        }

        lastHeartbeats.put(session.getId(), System.currentTimeMillis());
        playerSessions.compute(playerId, (id, sessions) -> {
            if (sessions == null) sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
            sessions.add(session);
            return sessions;
        });

        if (!validSession(session)) { closeExpired(session); return; }
        log.info("WebSocket 连接建立: playerId={}, sessionId={}, 当前在线会话数={}",
                playerId, session.getId(), getSessionCount(playerId));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long playerId = getPlayerId(session);
        if (playerId == null) return;

        lastHeartbeats.remove(session.getId());
        playerSessions.computeIfPresent(playerId, (id, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });

        log.info("WebSocket 连接关闭: playerId={}, sessionId={}, status={}",
                playerId, session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!validSession(session)) { closeExpired(session); return; }
        String payload = message.getPayload();

        // 兼容旧客户端纯文本心跳，当前客户端使用 JSON 消息。
        boolean ping = "ping".equalsIgnoreCase(payload.trim());
        if (!ping) {
            try {
                ping = "ping".equals(objectMapper.readTree(payload).path("type").asText());
            } catch (IOException e) {
                return;
            }
        }
        if (ping && getSessions(getPlayerId(session)).contains(session)) {
            lastHeartbeats.put(session.getId(), System.currentTimeMillis());
            send(session, new TextMessage("{\"type\":\"pong\"}"));
        }
        // 其他消息暂不处理
    }

    /**
     * 获取指定玩家所有的活跃会话。
     */
    public Set<WebSocketSession> getSessions(Long playerId) {
        Set<WebSocketSession> sessions = playerSessions.get(playerId);
        return sessions != null ? sessions : Collections.emptySet();
    }

    /**
     * 向指定玩家发送文本消息。
     * 如果玩家有多个会话（多标签页），全部推送。
     */
    public void broadcast(String message) {
        TextMessage textMessage = new TextMessage(message);
        for (Set<WebSocketSession> sessions : playerSessions.values()) {
            for (WebSocketSession session : sessions) {
                send(session, textMessage);
            }
        }
    }

    public void sendToPlayer(Long playerId, String message) {
        Set<WebSocketSession> sessions = getSessions(playerId);
        if (sessions.isEmpty()) return;

        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : sessions) {
            send(session, textMessage);
        }
    }

    private void send(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) return;
        if (!validSession(session)) { closeExpired(session); return; }
        try {
            // 聊天禁用也覆盖实时广播，不能通过 WebSocket 绕过聊天历史接口限制。
            if (players != null && "chat".equals(objectMapper.readTree(message.getPayload()).path("type").asText())) {
                var player = players.findById(getPlayerId(session)).orElseThrow();
                try { protection.requireAccess(player, (String) session.getAttributes().get("playSession"), "/api/game/chat/push"); }
                catch (com.wargame.security.GameAccessException e) {
                    if (!"CHAT_RESTRICTED".equals(e.getCode())) closeExpired(session);
                    return;
                }
            }
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (IOException e) {
            log.warn("推送消息失败: sessionId={}", session.getId(), e);
        }
    }

    /**
     * 判断玩家是否在线（至少有一个活跃会话）。
     */
    public boolean isPlayerOnline(Long playerId) {
        Set<WebSocketSession> sessions = playerSessions.get(playerId);
        return sessions != null && sessions.stream().anyMatch(session -> session.isOpen()
                && System.currentTimeMillis() - lastHeartbeats.getOrDefault(session.getId(), 0L) < PRESENCE_TIMEOUT_MS);
    }

    /**
     * 获取指定玩家的会话数量。
     */
    public int getSessionCount(Long playerId) {
        Set<WebSocketSession> sessions = playerSessions.get(playerId);
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * 从会话属性中获取 playerId。
     */
    private Long getPlayerId(WebSocketSession session) {
        Object playerId = session.getAttributes().get("playerId");
        return playerId instanceof Long ? (Long) playerId : null;
    }
}
