package com.wargame;

import com.wargame.config.GameServerIdentity;
import com.wargame.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ServerRegionTest extends BaseServiceTest {
    @Autowired MockMvc http;
    @Autowired JwtUtil jwt;
    @Autowired GameServerIdentity server;

    @Test
    void wrongRegionCannotRegisterOrAccessAccountEndpoints() throws Exception {
        long before = playerRepository.count();
        String body = "{\"username\":\"wrong-region\",\"password\":\"password123\",\"agreementVersion\":\"2026-09-28-v1\",\"serverId\":\"jiangsu-2\"}";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("大区")));
        http.perform(post("/api/auth/register").header("X-Game-Server", "jiangsu-2")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        http.perform(get("/api/auth/server").header("X-Game-Server", "jiangsu-2"))
                .andExpect(status().isBadRequest());
        assertEquals(before, playerRepository.count());
        http.perform(get("/api/auth/server").header("X-Game-Server", "jiangsu-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("江苏一区"));
    }

    @Test
    void selectedRegionRegistersAndLogsInOnItsOwnInstance() throws Exception {
        createTestWorld();
        String body = "{\"username\":\"region-player\",\"password\":\"password123\",\"agreementVersion\":\"2026-09-28-v1\",\"serverId\":\"jiangsu-1\"}";
        http.perform(post("/api/auth/register").header("X-Game-Server", "jiangsu-1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());
        http.perform(post("/api/auth/login").header("X-Game-Server", "jiangsu-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"region-player\",\"password\":\"password123\",\"serverId\":\"jiangsu-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());
        assertTrue(playerRepository.existsByUsername("region-player"));
    }

    @Test
    void tokenCannotBeReusedOnAnotherRegionWithTheSameSigningSecret() {
        String token = jwt.generateToken("alice", 1L);
        assertTrue(jwt.validateToken(token));
        JwtUtil other = new JwtUtil();
        ReflectionTestUtils.setField(other, "secret", "wargame-test-secret-key-at-least-256-bits-long-for-hs256-testing");
        ReflectionTestUtils.setField(other, "expiration", 86_400_000L);
        ReflectionTestUtils.setField(other, "serverId", "jiangsu-2");
        assertFalse(other.validateToken(token));
        assertEquals("jiangsu-1", server.id());
    }
}
