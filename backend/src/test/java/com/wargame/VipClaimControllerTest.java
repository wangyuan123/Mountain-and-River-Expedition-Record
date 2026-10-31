package com.wargame;

import com.wargame.config.VipConfig;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.Resources;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerVipRewardRepository;
import com.wargame.util.JsonUtil;
import com.wargame.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class VipClaimControllerTest extends BaseServiceTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired PlayerItemRepository items;
    @Autowired PlayerVipRewardRepository packClaims;

    private Player player() {
        Player player = createTestPlayer("vip-http-claims", 30);
        player.setVipLevel(7);
        player.setTotalRechargeCents(150000L);
        return playerRepository.saveAndFlush(player);
    }

    private String token(Player player) {
        return jwt.generateToken(player.getUsername(), player.getId(), player.getAuthVersion());
    }

    private Map<String, Integer> rewards(String action) {
        return switch (action) {
            case "daily-salary/claim" -> VipConfig.getLevelDef(7).dailyRewards();
            case "level-pack/claim" -> VipConfig.getLevelDef(1).levelPackRewards();
            case "milestone/claim" -> VipConfig.getMilestoneDef(150000L).rewards();
            default -> {
                Map<String, Integer> total = new HashMap<>();
                for (int level = 1; level <= 7; level++) {
                    VipConfig.getLevelDef(level).levelPackRewards().forEach((key, count) -> total.merge(key, count, Integer::sum));
                }
                yield total;
            }
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"daily-salary/claim", "level-pack/claim", "level-pack/claim-all", "milestone/claim"})
    void rewardEndpointsAwardExactlyOnceAndReturnFreshState(String action) throws Exception {
        Player player = player();
        String body = "{\"targetLevel\":1,\"milestoneCents\":150000,\"targetCitySlot\":0}";
        Map<String, Integer> expected = rewards(action);
        var response = mvc.perform(post("/api/game/vip/" + action).header("Authorization", "Bearer " + token(player))
                .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.state.player.vipDailySalaryClaimed").value(action.equals("daily-salary/claim")))
                .andExpect(jsonPath("$.state.player.id").value(player.getId())).andReturn().getResponse();
        Map<String, Object> data = JsonUtil.parseObjMap(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        Map<?, ?> state = (Map<?, ?>) data.get("state");
        Map<?, ?> resourceState = (Map<?, ?>) state.get("resources"), itemState = (Map<?, ?>) state.get("items");
        Resources after = resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow();
        assertEquals(500L + expected.getOrDefault("gold", 0), after.getGold());
        assertEquals(after.getGold().longValue(), ((Number) resourceState.get("gold")).longValue());
        expected.forEach((key, count) -> {
            if (!java.util.Set.of("gold", "food", "steel", "oil", "rare").contains(key)) {
                assertEquals(count.intValue(), ((Number) itemState.get(key)).intValue(), key);
                assertEquals(count, items.findByPlayerIdAndItemKey(player.getId(), key).orElseThrow().getCount(), key);
            }
        });
        mvc.perform(post("/api/game/vip/" + action).header("Authorization", "Bearer " + token(player))
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertEquals(after.getGold(), resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getGold());
    }

    @Test
    void invalidRewardCityCannotConsumePackOrCreateUnreachableResources() throws Exception {
        Player player = player();
        mvc.perform(post("/api/game/vip/level-pack/claim").header("Authorization", "Bearer " + token(player))
                .contentType("application/json").content("{\"targetLevel\":1,\"targetCitySlot\":999}"))
                .andExpect(status().isBadRequest());
        assertFalse(packClaims.existsByPlayerIdAndVipLevel(player.getId(), 1));
        assertTrue(resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 999).isEmpty());
    }

    @Test
    void rewardsCanGoToOwnedSubcityWithoutSwitchingCurrentCity() throws Exception {
        Player player = player();
        PlayerCity city = new PlayerCity();
        city.setWorldId(1L); city.setOwnerId(player.getId()); city.setCitySlot(1);
        city.setName("受领分城"); city.setX(25); city.setY(25);
        city.setLastTick(com.wargame.util.GameClock.now());
        playerCityRepository.saveAndFlush(city);
        Resources resources = new Resources();
        resources.setPlayerId(player.getId()); resources.setCitySlot(1);
        resources.setGold(100); resources.setFood(0); resources.setSteel(0); resources.setOil(0); resources.setRare(0);
        resourcesRepository.saveAndFlush(resources);
        mvc.perform(post("/api/game/vip/level-pack/claim").header("Authorization", "Bearer " + token(player))
                .contentType("application/json").content("{\"targetLevel\":1,\"targetCitySlot\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targetCitySlot").value(1))
                .andExpect(jsonPath("$.state.resources.gold").value(500))
                .andExpect(jsonPath("$.state.items.recruitOrd").value(1));
        assertEquals(400100L, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 1).orElseThrow().getGold());
        assertEquals(500L, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getGold());
    }

    @ParameterizedTest
    @ValueSource(strings = {"daily-salary/claim", "level-pack/claim", "level-pack/claim-all", "milestone/claim"})
    void allRewardTypesRejectMissingCityBeforeConsumingEligibility(String action) throws Exception {
        Player player = player();
        mvc.perform(post("/api/game/vip/" + action).header("Authorization", "Bearer " + token(player))
                .contentType("application/json").content("{\"targetLevel\":1,\"milestoneCents\":150000,\"targetCitySlot\":999}"))
                .andExpect(status().isBadRequest());
        assertNull(playerRepository.findById(player.getId()).orElseThrow().getLastVipDailyRewardDate());
        assertTrue(packClaims.findByPlayerId(player.getId()).isEmpty());
        assertTrue(items.findByPlayerId(player.getId()).isEmpty());
        assertTrue(resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 999).isEmpty());
    }

    @Test
    void homeAndModalDailyStatusStayConsistentAcrossReloadAndShanghaiMidnight() throws Exception {
        Player player = player();
        player.setLastVipDailyRewardDate(java.time.LocalDate.of(2026, 10, 26));
        playerRepository.saveAndFlush(player);
        for (String instant : new String[]{"2026-10-26T15:59:00Z", "2026-10-26T16:01:00Z"}) {
            boolean claimed = instant.contains("15:59");
            try (AutoCloseable ignored = com.wargame.util.GameClock.use(
                    () -> java.time.Instant.parse(instant).toEpochMilli())) {
                mvc.perform(get("/api/game/state").header("Authorization", "Bearer " + token(player)))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.player.vipDailySalaryClaimed").value(claimed));
                mvc.perform(get("/api/game/vip/status").header("Authorization", "Bearer " + token(player)))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.dailyClaimed").value(claimed));
            }
        }
    }
}
