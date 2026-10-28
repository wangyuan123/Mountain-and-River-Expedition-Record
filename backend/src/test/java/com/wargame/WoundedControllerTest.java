package com.wargame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.entity.ArmyUnit;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.Resources;
import com.wargame.service.AuthService;
import com.wargame.service.WoundedService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "game.auth.register-per-ip-per-hour=20")
class WoundedControllerTest extends BaseServiceTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired WoundedService camp;

    private JsonNode register(String name) throws Exception {
        return json.readTree(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(name, "test-password-123")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gold", "diamond"})
    void authenticatedCampAndImmediateTreatmentReturnUpdatedArmyAndBalance(String currency) throws Exception {
        createTestWorld();
        JsonNode registration = register("wounded-http");
        long playerId = registration.path("playerId").asLong();
        String token = registration.path("token").asText();
        int initialScouts = armyUnitRepository.findByPlayerIdAndType(playerId, "scout").stream()
                .mapToInt(unit -> unit.getCount()).sum();
        createTechnology(playerId, "log_medical", 10);
        camp.recordLosses(playerId, null, null, Map.of("scout", 20), Map.of(), null, System.currentTimeMillis());
        String result = http.perform(get("/api/game/army/wounded").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode before = json.readTree(result);
        assertEquals(10, before.path("total").asInt());
        assertEquals(1, before.path("batches").get(0).path("diamondCost").asInt());
        assertEquals(0, before.path("citySlot").asInt());
        assertTrue(before.path("cityName").asText().length() > 0);
        JsonNode untreated = json.valueToTree(gameStateService.getGameState(playerId));
        assertEquals(initialScouts, untreated.path("army").path("scout").asInt());
        assertEquals(10, untreated.path("woundedCount").asInt());
        long id = before.path("batches").get(0).path("id").asLong();
        long quotedCost = before.path("batches").get(0).path(currency + "Cost").asLong();
        var resources = resourcesRepository.findByPlayerId(playerId).orElseThrow();
        resources.setDiamond(100);
        resourcesRepository.save(resources);
        long gold = resources.getGold();
        int diamond = resources.getDiamond();
        JsonNode after = json.readTree(http.perform(post("/api/game/army/wounded/" + id + "/heal")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("count", 10, "currency", currency))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(after.path("success").asBoolean());
        assertEquals(0, after.path("camp").path("total").asInt());
        assertEquals(initialScouts + 10, after.path("state").path("army").path("scout").asInt());
        assertEquals(quotedCost, after.path("cost").asLong());
        assertEquals("gold".equals(currency) ? gold - quotedCost : gold, after.path("state").path("resources").path("gold").asLong());
        assertEquals("diamond".equals(currency) ? diamond - quotedCost : diamond, after.path("state").path("resources").path("diamond").asLong());
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

    @ParameterizedTest
    @ValueSource(strings = {"gold", "diamond"})
    void cityHeaderSeparatesCampFundsAndRestoredTroopsAndRejectsCrossCityTreatment(String currency) throws Exception {
        createTestWorld();
        JsonNode registration = register("wounded-" + currency);
        long playerId = registration.path("playerId").asLong();
        String token = registration.path("token").asText();
        PlayerCity main = playerCityRepository.findByOwnerIdAndCitySlot(playerId, 0).orElseThrow();
        PlayerCity branch = new PlayerCity();
        branch.setOwnerId(playerId);
        branch.setCitySlot(1);
        branch.setWorldId(main.getWorldId());
        branch.setName("Wounded Branch");
        branch.setX(20);
        branch.setY(20);
        branch.setLevel(1);
        branch.setLastTick(System.currentTimeMillis());
        playerCityRepository.save(branch);
        Resources mainBalance = resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
        mainBalance.setGold(5000);
        mainBalance.setDiamond(100);
        resourcesRepository.save(mainBalance);
        Resources branchBalance = new Resources();
        branchBalance.setPlayerId(playerId);
        branchBalance.setCitySlot(1);
        branchBalance.setGold(1000);
        branchBalance.setDiamond(0);
        resourcesRepository.save(branchBalance);
        ArmyUnit reserves = new ArmyUnit(null, playerId, "scout", 7);
        reserves.setCitySlot(1);
        armyUnitRepository.save(reserves);
        int mainScouts = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "scout").stream()
                .mapToInt(ArmyUnit::getCount).sum();
        createTechnology(playerId, "log_medical", 10);
        camp.recordLosses(playerId, main.getX(), main.getY(), Map.of("scout", 40), Map.of(), null, System.currentTimeMillis());
        camp.recordLosses(playerId, branch.getX(), branch.getY(), Map.of("scout", 10), Map.of(), null, System.currentTimeMillis());

        JsonNode mainCamp = json.readTree(http.perform(get("/api/game/army/wounded")
                .header("Authorization", "Bearer " + token).header("X-City-Id", main.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode branchCamp = json.readTree(http.perform(get("/api/game/army/wounded")
                .header("Authorization", "Bearer " + token).header("X-City-Id", branch.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(20, mainCamp.path("total").asInt());
        assertEquals(5, branchCamp.path("total").asInt());
        assertEquals(1, branchCamp.path("batches").size());
        assertEquals(branch.getId().longValue(), branchCamp.path("cityId").asLong());
        assertEquals(1, branchCamp.path("citySlot").asInt());
        assertEquals("Wounded Branch", branchCamp.path("cityName").asText());
        assertEquals(1000, branchCamp.path("resources").path("gold").asInt());
        assertEquals(100, branchCamp.path("resources").path("diamond").asInt());
        long batchId = branchCamp.path("batches").get(0).path("id").asLong();
        String treatment = json.writeValueAsString(Map.of("count", 2, "currency", currency));
        http.perform(post("/api/game/army/wounded/" + batchId + "/heal")
                .header("Authorization", "Bearer " + token).header("X-City-Id", main.getId())
                .contentType(MediaType.APPLICATION_JSON).content(treatment))
                .andExpect(status().isBadRequest());
        assertEquals(5000, mainBalance.getGold());
        assertEquals(100, mainBalance.getDiamond());
        assertEquals(1000, branchBalance.getGold());
        assertEquals(7, reserves.getCount());

        JsonNode treated = json.readTree(http.perform(post("/api/game/army/wounded/" + batchId + "/heal")
                .header("Authorization", "Bearer " + token).header("X-City-Id", branch.getId())
                .contentType(MediaType.APPLICATION_JSON).content(treatment))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long cost = "gold".equals(currency) ? camp.goldCost("scout", 2) : camp.diamondCost("scout", 2);
        assertEquals(3, treated.path("camp").path("total").asInt());
        assertEquals(branch.getId().longValue(), treated.path("camp").path("cityId").asLong());
        assertEquals(9, treated.path("state").path("army").path("scout").asInt());
        assertEquals("gold".equals(currency) ? 1000 - cost : 1000, treated.path("camp").path("resources").path("gold").asLong());
        assertEquals("diamond".equals(currency) ? 100 - cost : 100, treated.path("camp").path("resources").path("diamond").asLong());
        assertEquals(5000, mainBalance.getGold());
        assertEquals(mainScouts, armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "scout").stream()
                .mapToInt(ArmyUnit::getCount).sum());
    }
}
