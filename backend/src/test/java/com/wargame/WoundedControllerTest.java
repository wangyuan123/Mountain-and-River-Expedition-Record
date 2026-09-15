package com.wargame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.service.WoundedService;
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
class WoundedControllerTest extends BaseServiceTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired WoundedService camp;

    private JsonNode register(String name) throws Exception {
        return json.readTree(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", name, "password", "test-password-123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void authenticatedCampAndImmediateTreatmentReturnUpdatedArmyAndBalance() throws Exception {
        createTestWorld();
        JsonNode registration = register("wounded-http");
        long playerId = registration.path("playerId").asLong();
        String token = registration.path("token").asText();
        createTechnology(playerId, "log_medical", 10);
        camp.recordLosses(playerId, null, null, Map.of("scout", 20), Map.of(), null, System.currentTimeMillis());
        String result = http.perform(get("/api/game/army/wounded").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode before = json.readTree(result);
        assertEquals(10, before.path("total").asInt());
        long id = before.path("batches").get(0).path("id").asLong();
        long quotedCost = before.path("batches").get(0).path("goldCost").asLong();
        int gold = resourcesRepository.findByPlayerId(playerId).orElseThrow().getGold();
        JsonNode after = json.readTree(http.perform(post("/api/game/army/wounded/" + id + "/heal")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\":10,\"currency\":\"gold\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(after.path("success").asBoolean());
        assertEquals(0, after.path("camp").path("total").asInt());
        assertEquals(10, after.path("state").path("army").path("scout").asInt());
        assertEquals(quotedCost, after.path("cost").asLong());
        assertEquals(gold - quotedCost, after.path("state").path("resources").path("gold").asLong());
        assertEquals(0, after.path("state").path("woundedCount").asInt());
    }

    @Test
    void anotherPlayerCannotReadOrTreatAnOwnersBatch() throws Exception {
        createTestWorld();
        JsonNode owner = register("wounded-owner");
        JsonNode other = register("wounded-visitor");
        long ownerId = owner.path("playerId").asLong();
        String otherToken = other.path("token").asText();
        createTechnology(ownerId, "log_medical", 10);
        camp.recordLosses(ownerId, null, null, Map.of("scout", 20), Map.of(), null, System.currentTimeMillis());
        JsonNode data = json.valueToTree(camp.getCamp(ownerId));
        long id = data.path("batches").get(0).path("id").asLong();
        String response = http.perform(get("/api/game/army/wounded").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(0, json.readTree(response).path("total").asInt());
        http.perform(post("/api/game/army/wounded/" + id + "/heal").header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"count\":1,\"currency\":\"gold\"}"))
                .andExpect(status().isBadRequest());
    }
}
