package com.wargame;

import com.fasterxml.jackson.databind.*;
import com.wargame.model.entity.*;
import com.wargame.service.CityService;
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
class MultiCityControllerTest extends BaseServiceTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CityService cities;

    @Test void shieldEndpointReturnsPersistedStateAndRejectsInsufficientBalance() throws Exception {
        createTestWorld();
        JsonNode registration = json.readTree(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"shield-http\",\"password\":\"test-password-123\",\"agreementVersion\":\"2026-09-28-v1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Long id = registration.path("playerId").asLong();
        String auth = "Bearer " + registration.path("token").asText();
        PlayerCity city = playerCityRepository.findByOwnerIdAndCitySlot(id, 0).orElseThrow();
        Resources wallet = getResources(id);
        wallet.setDiamond(300);
        resourcesRepository.save(wallet);
        String body = json.writeValueAsString(Map.of("cityIds", java.util.List.of(city.getId())));
        long before = com.wargame.util.GameClock.now();
        JsonNode result = json.readTree(http.perform(post("/api/game/cities/shield").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(result.path("success").asBoolean());
        assertEquals(200, result.path("cost").asInt());
        assertEquals(100, result.path("state").path("resources").path("diamond").asInt());
        long shieldUntil = cityStateRepository.findByPlayerIdAndCitySlot(id, 0).orElseThrow().getShieldUntil();
        assertTrue(shieldUntil >= before + 8L * 3600 * 1000);
        assertEquals(shieldUntil, result.path("state").path("cityState").path("shieldUntil").asLong());
        JsonNode error = json.readTree(http.perform(post("/api/game/cities/shield").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(error.path("error").asText().contains("钻石余额不足"));
        assertEquals(100, getResources(id).getDiamond());
        assertEquals(shieldUntil, cityStateRepository.findByPlayerIdAndCitySlot(id, 0).orElseThrow().getShieldUntil());
    }

    @Test void cityHeaderPinsMutationsAndRejectsForeignCitiesEvenAfterAnotherTabSwitches() throws Exception {
        WorldMap world = createTestWorld();
        JsonNode registration = json.readTree(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"city-http\",\"password\":\"test-password-123\",\"agreementVersion\":\"2026-09-28-v1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Long id = registration.path("playerId").asLong(); String auth = "Bearer " + registration.path("token").asText();
        Player player = playerRepository.findById(id).orElseThrow(); player.setMilitaryRank(4); playerRepository.save(player);
        giveResources(id, 100000, 100000, 100000, 100000, 100000);
        PlayerCity main = playerCityRepository.findByOwnerIdAndCitySlot(id, 0).orElseThrow();
        WildTile site = new WildTile(); site.setWorldId(world.getId()); site.setX(45); site.setY(46); site.setType("hill");
        site.setLevel(1); site.setOccupied(true); site.setOccupiedBy(id); wildTileRepository.save(site);
        JsonNode founded = json.readTree(http.perform(post("/api/game/cities").header("Authorization", auth).header("X-City-Id", main.getId())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("wildId",site.getId(),"name","北城"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Long branchId = founded.path("cityId").asLong();
        http.perform(post("/api/game/cities/switch").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("cityId",branchId)))).andExpect(status().isBadRequest());
        PlayerCity branch = playerCityRepository.findById(branchId).orElseThrow(); branch.setReadyAt(0L); playerCityRepository.save(branch);
        JsonNode switched = json.readTree(http.perform(post("/api/game/cities/switch").header("Authorization",auth).header("X-City-Id",main.getId())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("cityId",branchId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(branchId.longValue(), switched.path("state").path("player").path("activeCityId").asLong());
        http.perform(post("/api/game/settings/tax").header("Authorization",auth).header("X-City-Id",main.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tax\":10}")).andExpect(status().isOk());
        assertEquals(10, player.getTax()); assertEquals(30, branch.getTax());
        JsonNode reloaded = json.readTree(http.perform(get("/api/game/state").header("Authorization",auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(branchId.longValue(), reloaded.path("player").path("activeCityId").asLong());
        Player other = createTestPlayer("other-city-owner",30);
        PlayerCity foreign = new PlayerCity(); foreign.setOwnerId(other.getId()); foreign.setCitySlot(0); foreign.setWorldId(world.getId());
        foreign.setName("外城"); foreign.setX(90); foreign.setY(90); playerCityRepository.save(foreign);
        http.perform(get("/api/game/state").header("Authorization",auth).header("X-City-Id",foreign.getId())).andExpect(status().isBadRequest());
        http.perform(get("/api/game/state").header("Authorization",auth).header("X-City-Id","invalid")).andExpect(status().isBadRequest());
        http.perform(get("/api/game/cities")).andExpect(status().isUnauthorized());
    }

    @Test void oceanApiSupportsCoordinateFoundingAndProtectsOccupiedCells() throws Exception {
        WorldMap world=createTestWorld();int size = com.wargame.service.WorldTerrainService.SIZE;
        char[] mask=new char[size*size];java.util.Arrays.fill(mask,'0');
        for(int y=0;y<size;y++)for(int x=100;x<size;x++)mask[y*size+x]='1';
        world.setSize(com.wargame.model.constants.WorldConfig.WORLD_SIZE);
        world.setTerrainVersion(com.wargame.service.WorldTerrainService.TERRAIN_VERSION);
        world.setTerrainData(new String(mask));worldMapRepository.save(world);
        http.perform(get("/api/game/world/map/terrain")).andExpect(status().isUnauthorized());
        JsonNode registration=json.readTree(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ocean-http\",\"password\":\"test-password-123\",\"agreementVersion\":\"2026-09-28-v1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Long id=registration.path("playerId").asLong();String auth="Bearer "+registration.path("token").asText();
        Player player=playerRepository.findById(id).orElseThrow();player.setMilitaryRank(4);playerRepository.save(player);
        giveResources(id, 100000, 100000, 100000, 100000, 100000);
        PlayerCity main=playerCityRepository.findByOwnerIdAndCitySlot(id,0).orElseThrow();
        assertTrue(main.getX()<99);assertTrue(main.getY()<size-1);
        JsonNode map=json.readTree(http.perform(get("/api/game/world/map/terrain").header("Authorization",auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertEquals(new String(mask),map.path("cells").asText());
        int y=main.getY()<100?150:40;
        JsonNode site=json.readTree(http.perform(get("/api/game/cities/site?x=98&y="+y).header("Authorization",auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertTrue(site.path("valid").asBoolean());
        JsonNode result=json.readTree(http.perform(post("/api/game/cities").header("Authorization",auth).header("X-City-Id",main.getId())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("x",98,"y",y,"name","海岸城"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertTrue(result.path("success").asBoolean());
        assertEquals(2,result.path("state").path("cityOverview").path("count").asInt());
        assertTrue(playerCityRepository.findById(result.path("cityId").asLong()).isPresent());
        http.perform(post("/api/game/cities").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("x",98,"y",y+1,"name","重叠城")))).andExpect(status().isBadRequest());
    }
}
