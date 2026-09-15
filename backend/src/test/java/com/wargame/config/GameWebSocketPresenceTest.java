package com.wargame.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameWebSocketPresenceTest {
    private WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.getAttributes()).thenReturn(Map.of("playerId", 7L));
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void onlineUntilLastTabCloses() {
        GameWebSocketHandler h = new GameWebSocketHandler();
        WebSocketSession a = session("a"), b = session("b");
        assertFalse(h.isPlayerOnline(7L));
        h.afterConnectionEstablished(a);
        h.afterConnectionEstablished(b);
        assertTrue(h.isPlayerOnline(7L));
        h.afterConnectionClosed(a, CloseStatus.NORMAL);
        assertTrue(h.isPlayerOnline(7L));
        h.afterConnectionClosed(b, CloseStatus.NORMAL);
        assertFalse(h.isPlayerOnline(7L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void heartbeatExpiryAndRecovery() throws Exception {
        GameWebSocketHandler h = new GameWebSocketHandler();
        WebSocketSession s = session("a");
        h.afterConnectionEstablished(s);
        Map<String, Long> beats = (Map<String, Long>) ReflectionTestUtils.getField(h, "lastHeartbeats");
        beats.put("a", System.currentTimeMillis() - 91_000);
        assertFalse(h.isPlayerOnline(7L));
        h.handleTextMessage(s, new TextMessage("{ \"type\": \"ping\" }"));
        assertTrue(h.isPlayerOnline(7L));
        verify(s).sendMessage(argThat(m -> "{\"type\":\"pong\"}".equals(m.getPayload())));
        when(s.isOpen()).thenReturn(false);
        assertFalse(h.isPlayerOnline(7L));
    }

    @Test
    void legacyHeartbeatAlsoWorks() throws Exception {
        GameWebSocketHandler h = new GameWebSocketHandler();
        WebSocketSession s = session("legacy");
        h.afterConnectionEstablished(s);
        h.handleTextMessage(s, new TextMessage("ping"));
        verify(s).sendMessage(argThat(m -> "{\"type\":\"pong\"}".equals(m.getPayload())));
    }
}
