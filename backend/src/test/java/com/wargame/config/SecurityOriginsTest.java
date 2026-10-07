package com.wargame.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecurityOriginsTest {
    private CorsConfiguration cors(String origins) {
        return new CorsConfig(new SecurityOrigins(origins)).corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/chat"));
    }

    @Test
    void onlyExplicitOriginsCanAccessBearerApis() {
        CorsConfiguration config = cors("https://game.example,http://localhost:8081");
        assertEquals("https://game.example", config.checkOrigin("https://game.example"));
        assertNull(config.checkOrigin("https://evil.example"));
        assertNull(config.checkOrigin("https://game.example.evil.example"));
        assertNull(config.checkOrigin("http://game.example"));
        assertEquals(Boolean.FALSE, config.getAllowCredentials());
        assertNotNull(config.checkHttpMethod(org.springframework.http.HttpMethod.POST));
        assertNull(config.checkHttpMethod(org.springframework.http.HttpMethod.TRACE));
        assertNotNull(config.checkHeaders(List.of("Authorization", "Content-Type", "X-Game-Server", "X-City-Id", "X-Play-Session")));
        assertNull(config.checkHeaders(List.of("X-Untrusted")));
    }

    @Test
    void emptyProductionListRejectsForeignOrigins() {
        assertTrue(new SecurityOrigins("").values().isEmpty());
        assertNull(cors("").checkOrigin("https://evil.example"));
    }

    @Test
    void websocketOriginCheckAllowsSameOriginButRejectsForeignSites() throws Exception {
        var interceptor = new org.springframework.web.socket.server.support.OriginHandshakeInterceptor(
                new SecurityOrigins("https://trusted.example").values());
        for (String origin : List.of("http://localhost", "https://trusted.example", "https://evil.example")) {
            var request = new MockHttpServletRequest("GET", "/ws/game");
            request.addHeader("Origin", origin);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            boolean accepted = interceptor.beforeHandshake(
                    new org.springframework.http.server.ServletServerHttpRequest(request),
                    new org.springframework.http.server.ServletServerHttpResponse(response),
                    new org.springframework.web.socket.handler.TextWebSocketHandler(), new java.util.HashMap<>());
            assertEquals(!origin.equals("https://evil.example"), accepted, origin);
            if (!accepted) assertEquals(403, response.getStatus());
        }
    }

    @Test
    void invalidOriginsCannotSilentlyOpenTheWhitelist() {
        for (String value : List.of("*", "https://*.example", "null", "file:///tmp", "https://game.example/", "https://game.example/path", "https://user@game.example", "https://game.example?x=1", "https://game.example#x", "https://game.example:70000")) {
            assertThrows(IllegalArgumentException.class, () -> new SecurityOrigins(value), value);
        }
        assertEquals(List.of("https://game.example"), new SecurityOrigins(" https://game.example,https://game.example, ").values());
    }
}
