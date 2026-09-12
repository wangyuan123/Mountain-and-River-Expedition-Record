package com.wargame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.entity.*;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.service.ArmyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class GameFlowTest extends BaseServiceTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired ArmyService army;
    @Autowired ArmyProductionQueueRepository production;

    @Test
    void registerBuildRecruitDispatchReadReportAndReceiveLoot() throws Exception {
        long worldId = createTestWorld().getId();
        JsonNode registered = postJson("/api/auth/register", null,
                Map.of("username", "flow-player", "password", "test-password-123"));
        String token = registered.path("token").asText();
        assertFalse(token.isBlank());
        long playerId = registered.path("playerId").asLong();

        JsonNode built = postJson("/api/game/build/upgrade", token, Map.of("building", "factory", "slot", 0));
        assertTrue(built.path("success").asBoolean(), built.toString());
        for (Construction queue : constructionRepository.findByPlayerId(playerId)) {
            queue.setFinishAt(System.currentTimeMillis() - 1);
            constructionRepository.save(queue);
        }
        buildService.completeUpgrade(playerId, System.currentTimeMillis());

        JsonNode recruited = postJson("/api/game/army/recruit", token, Map.of("unit", "infantry", "count", 10));
        assertTrue(recruited.path("success").asBoolean(), recruited.toString());
        JsonNode transport = postJson("/api/game/army/recruit", token, Map.of("unit", "truck", "count", 1));
        assertTrue(transport.path("success").asBoolean(), transport.toString());
        for (ArmyProductionQueue queue : production.findByPlayerIdOrderByStartedAtAscIdAsc(playerId)) {
            queue.setFinishesAt(System.currentTimeMillis() - 1);
            production.save(queue);
        }
        army.completeProduction(playerId, System.currentTimeMillis());
        assertEquals(60, armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount());

        Player player = playerRepository.findById(playerId).orElseThrow();
        String resourceType = WildTypeDef.WILD_TYPES.entrySet().stream()
                .filter(entry -> entry.getValue().res() != null).findFirst().orElseThrow().getKey();
        WildTile target = createWildTile(worldId, resourceType,
                (player.getCityPosX() + 1) % 200, player.getCityPosY(), 1, Map.of(), 1000);
        postJson("/api/game/world/dispatch", token, Map.of("targetKind", "wild", "targetId", target.getId(),
                "action", "plunder", "army", Map.of("infantry", 10, "truck", 1), "carryRes", Map.of()));
        March march = marchRepository.findByPlayerId(playerId).get(0);
        march.setArriveAt(System.currentTimeMillis() - 1);
        marchRepository.save(march);
        marchService.processMarches(playerId, System.currentTimeMillis());
        assertTrue(marchRepository.findByPlayerId(playerId).get(0).getReturning());

        String reports = http.perform(get("/api/game/reports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode reportList = json.readTree(reports);
        assertFalse(reportList.isEmpty());
        assertEquals("battle", reportList.get(0).path("type").asText());
        assertTrue(reportList.get(0).path("win").asBoolean());

        Resources before = getResources(playerId);
        int beforeTotal = before.getFood() + before.getSteel() + before.getOil() + before.getRare();
        March returning = marchRepository.findByPlayerId(playerId).get(0);
        returning.setArriveAt(System.currentTimeMillis() - 1);
        marchRepository.save(returning);
        marchService.processMarches(playerId, System.currentTimeMillis());
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
        Resources after = getResources(playerId);
        assertTrue(after.getFood() + after.getSteel() + after.getOil() + after.getRare() > beforeTotal);
        assertEquals(60, armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount());
    }

    @Test
    void unreadReportsPersistAcrossStateReloadAndStayPrivate() throws Exception {
        createTestWorld();
        JsonNode alice = postJson("/api/auth/register", null,
                Map.of("username", "report-alice", "password", "test-password-123"));
        JsonNode bob = postJson("/api/auth/register", null,
                Map.of("username", "report-bob", "password", "test-password-123"));
        String token = alice.path("token").asText();
        String bobToken = bob.path("token").asText();
        long playerId = alice.path("playerId").asLong();
        Long reportId = null;
        for (int i = 0; i < 60; i++) {
            ScoutReport report = new ScoutReport();
            report.setPlayerId(playerId);
            report.setType(i % 2 == 0 ? "battle" : "scout");
            report.setData("{}");
            report.setCreatedAt(System.currentTimeMillis() + i);
            report.setReadAt(i == 0 ? null : 0L);
            reportId = scoutReportRepository.save(report).getId();
        }
        assertEquals(50, getJson("/api/game/reports?limit=50", token).size());
        assertEquals(60, getJson("/api/game/state", token).path("unreadReportCount").asInt());
        assertEquals(60, getJson("/api/game/reports/unread", token).path("unreadCount").asInt());
        assertEquals(0, getJson("/api/game/reports/unread", bobToken).path("unreadCount").asInt());
        assertFalse(postJson("/api/game/reports/" + reportId + "/read", bobToken, Map.of()).path("success").asBoolean());

        assertEquals(59, postJson("/api/game/reports/" + reportId + "/read", token, Map.of()).path("unreadCount").asInt());
        assertEquals(59, postJson("/api/game/reports/" + reportId + "/read", token, Map.of()).path("unreadCount").asInt());
        assertEquals(59, getJson("/api/game/state", token).path("unreadReportCount").asInt());
        assertEquals(0, postJson("/api/game/reports/read-all", token, Map.of()).path("unreadCount").asInt());
        assertEquals(0, getJson("/api/game/state", token).path("unreadReportCount").asInt());
    }

    private JsonNode getJson(String path, String token) throws Exception {
        return json.readTree(http.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String path, String token, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json.readTree(http.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
