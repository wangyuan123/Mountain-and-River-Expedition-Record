package com.wargame;

import com.wargame.model.entity.Player;
import com.wargame.repository.PvpPracticeSessionRepository;
import com.wargame.service.pvp.PvpJson;
import com.wargame.util.JwtUtil;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PvpControllerTest extends BaseServiceTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired PvpPracticeSessionRepository sessions;
    @org.springframework.boot.test.mock.mockito.SpyBean com.wargame.service.compliance.AntiAddictionService protection;
    private String token(Player p) { return jwt.generateToken(p.getUsername(), p.getId(), p.getAuthVersion()); }
    private Map<String, Object> postJson(String path, String token, Object body) throws Exception {
        String response = mvc.perform(post("/api/game/pvp/" + path).header("Authorization", "Bearer " + token)
                .header("X-Game-Server", "jiangsu-1").contentType("application/json").content(PvpJson.write(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return JsonUtil.parseObjMap(response);
    }
    private Map<String, Object> getJson(String path, String token) throws Exception {
        return JsonUtil.parseObjMap(mvc.perform(get("/api/game/pvp/" + path).header("Authorization", "Bearer " + token)
                .header("X-Game-Server", "jiangsu-1")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test @Timeout(45)
    void realControllerGuardsDoNotHoldWorldLockAcrossSnapshotAndPrivateBattleHasNoEnemyDrafts() throws Exception {
        Player a = createTestPlayer("http-a-" + UUID.randomUUID(), 30), b = createTestPlayer("http-b-" + UUID.randomUUID(), 30);
        createArmyUnit(a.getId(), "infantry", 100); createArmyUnit(b.getId(), "infantry", 100);
        String at = token(a), bt = token(b);
        getJson("lobby", at); getJson("lobby", bt);
        var defaults = ((Map<?, ?>) getJson("lobby", at).get("preset")).get("defaults");
        var preset = Map.of("requestId", UUID.randomUUID().toString(), "citySlot", 0, "battlefield", "LAND_AIR", "army", Map.of("infantry", 100), "presetVersion", 0, "defaults", defaults);
        var saved = postJson("preset", at, preset); assertEquals(saved, postJson("preset", at, preset)); assertTrue(saved.containsKey("snapshot"));
        var queue = Map.of("requestId", UUID.randomUUID().toString(), "citySlot", 0, "battlefield", "LAND_AIR", "army", Map.of("infantry", 100), "presetVersion", 1);
        var first = postJson("queue", at, queue); var duplicate = postJson("queue", at, queue); assertEquals(first, duplicate);
        var second = postJson("queue", bt, Map.of("requestId", UUID.randomUUID().toString(), "citySlot", 0, "battlefield", "LAND_AIR", "army", Map.of("infantry", 100), "presetVersion", 0));
        String ta = (String) first.get("ticketId"), tb = (String) second.get("ticketId");
        var pvp = applicationPvp(); assertTrue(pvp.pair(ta, tb));
        String session = (String) getJson("status", at).get("sessionId");
        postJson("accept", at, Map.of("requestId", UUID.randomUUID().toString(), "ticketId", ta, "sessionId", session));
        postJson("accept", bt, Map.of("requestId", UUID.randomUUID().toString(), "ticketId", tb, "sessionId", session));
        var battle = getJson("battle?sessionId=" + session, at);
        assertFalse(((Map<?, ?>) battle.get("enemy")).containsKey("defaults"));
        postJson("loaded", at, Map.of("requestId", UUID.randomUUID().toString(), "sessionId", session));
        postJson("loaded", bt, Map.of("requestId", UUID.randomUUID().toString(), "sessionId", session));
        assertEquals("COUNTDOWN", getJson("status", at).get("status"));
        postJson("surrender", at, Map.of("requestId", UUID.randomUUID().toString(), "sessionId", session));
        assertEquals("LOSS", getJson("report?sessionId=" + session, at).get("result"));
        assertEquals(100, armyUnitRepository.findByPlayerIdAndCitySlotAndType(a.getId(), 0, "infantry").get(0).getCount());
        mvc.perform(get("/api/game/pvp/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/game/pvp/status").header("Authorization", "Bearer " + at).header("X-Game-Server", "other")).andExpect(status().isBadRequest());
    }
    @Test void permissionCutoffInsidePracticeWriteRollsBackPreset() throws Exception {
        Player player = createTestPlayer("permission-" + UUID.randomUUID(), 30); createArmyUnit(player.getId(), "infantry", 100);
        String token = token(player); var lobby = getJson("lobby", token);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            if (count.incrementAndGet() == 3) throw new com.wargame.security.GameAccessException("PLAY_WINDOW_CLOSED", "演习写入跨过许可截止");
            return call.callRealMethod();
        }).when(protection).requireOperationAccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("/api/game/pvp/preset"));
        try {
            mvc.perform(post("/api/game/pvp/preset").header("Authorization", "Bearer " + token).header("X-Game-Server", "jiangsu-1")
                    .contentType("application/json").content(PvpJson.write(Map.of("requestId", UUID.randomUUID().toString(), "citySlot", 0, "battlefield", "LAND_AIR", "army", Map.of("infantry", 100),
                            "presetVersion", 0, "defaults", ((Map<?, ?>) lobby.get("preset")).get("defaults")))))
                    .andExpect(status().isForbidden());
            assertEquals(0L, ((Number) getJson("lobby", token).get("presetVersion")).longValue());
            assertEquals(100, armyUnitRepository.findByPlayerIdAndCitySlotAndType(player.getId(), 0, "infantry").get(0).getCount());
        } finally { org.mockito.Mockito.reset(protection); }
    }
    @Autowired com.wargame.service.pvp.PvpPracticeService pvp;
    private com.wargame.service.pvp.PvpPracticeService applicationPvp() { return pvp; }
}
