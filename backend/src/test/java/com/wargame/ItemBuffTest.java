package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.CityStateRepository;
import com.wargame.service.ItemBuffService;
import com.wargame.util.GameClock;
import com.wargame.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ItemBuffTest extends BaseServiceTest {
    @Autowired ItemBuffService buffs;
    @Autowired CityStateRepository states;
    @Autowired com.wargame.repository.PlayerItemRepository inventory;
    @Autowired MockMvc http;
    @Autowired JwtUtil jwt;
    private final AtomicLong time = new AtomicLong(2_000_000_000_000L);
    private AutoCloseable clock;
    private Player player;
    private PlayerCity main, branch;

    @BeforeEach void prepare() {
        clock = GameClock.use(time::get);
        var world = createTestWorld();
        player = createTestPlayer("buff-owner", 30);
        main = city(player.getId(), world.getId(), 0);
        branch = city(player.getId(), world.getId(), 1);
        player.setActiveCityId(main.getId()); playerRepository.saveAndFlush(player);
    }
    @AfterEach void restoreClock() throws Exception { clock.close(); }

    private PlayerCity city(Long owner, Long world, int slot) {
        var city = new PlayerCity();city.setOwnerId(owner);city.setWorldId(world);city.setCitySlot(slot);
        city.setName(slot == 0 ? "主城" : "分城");city.setX(10 + slot * 10);city.setY(20);city.setLevel(1);
        return playerCityRepository.saveAndFlush(city);
    }
    private CityState cityState(int slot, long shield, long march, Integer percent) {
        var s = new CityState();s.setPlayerId(player.getId());s.setCitySlot(slot);
        s.setShieldUntil(shield);s.setMarchBoostUntil(march);s.setMarchBoostPercent(percent);
        return states.saveAndFlush(s);
    }

    @Test void emptyResultDoesNotCreateStatesOrConsumeItems() {
        var result = buffs.snapshot(player.getId());
        assertEquals(2, result.cities().size());assertTrue(result.effects().isEmpty());
        assertEquals(time.get(), result.serverTime());assertTrue(states.findAllByPlayerId(player.getId()).isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {10, 25, 50})
    void aggregatesEveryCityAndExactMarchTierWithoutInventory(int percent) {
        cityState(0,time.get()+7200000,time.get()+3600000,percent);
        cityState(1,time.get()+200000,0L,0);
        var result = buffs.snapshot(player.getId());
        assertEquals(3,result.effects().size());
        var march = result.effects().stream().filter(e -> e.kind().equals("march")).findFirst().orElseThrow();
        assertEquals(main.getId(),march.cityId());assertEquals(percent,march.percent());
        assertEquals("city",march.scope());assertEquals(time.get()+3600000,march.until());
        assertTrue(result.effects().stream().anyMatch(e -> branch.getId().equals(e.cityId())));
        assertTrue(result.effects().stream().allMatch(e -> e.status().equals("active")));
        assertTrue(inventory.findByPlayerId(player.getId()).isEmpty());
    }

    @Test void ignoresExpiredProtectionAndUsesLegacyMarchTier() {
        cityState(0,time.get(),time.get()+1000,null);
        var result = buffs.snapshot(player.getId());
        assertEquals(1,result.effects().size());assertEquals(50,result.effects().get(0).percent());
        time.addAndGet(1000);
        assertTrue(buffs.snapshot(player.getId()).effects().isEmpty());
    }

    @Test void keepsCoveredMobilizationAndPromotesItWhenHigherTierEnds() {
        player.setMobilizationExpiries(com.wargame.util.JsonUtil.toJson(Map.of(
                "mobilizationOrder",time.get()+3000,"advancedMobilizationOrder",time.get()+2000,
                "rapidMobilizationOrder",time.get()+1000)));
        playerRepository.saveAndFlush(player);
        var result = buffs.snapshot(player.getId());
        assertEquals(3,result.effects().size());
        assertEquals(2,result.effects().stream().filter(e -> e.status().equals("covered")).count());
        assertEquals(50,result.effects().stream().filter(e -> e.status().equals("active")).findFirst().orElseThrow().percent());
        assertTrue(result.effects().stream().allMatch(e -> e.cityId()==null && e.scope().equals("account")));
        time.addAndGet(1000);
        assertEquals(25,buffs.snapshot(player.getId()).effects().stream().filter(e -> e.status().equals("active")).findFirst().orElseThrow().percent());
        time.addAndGet(2000);assertTrue(buffs.snapshot(player.getId()).effects().isEmpty());
    }

    @Test void excludesLostCitiesOtherAccountsAndNewbieProtection() {
        cityState(0,time.get()+1000,0,0);cityState(1,time.get()+1000,0,0);
        var other = createTestPlayer("buff-other",30);
        branch.setOwnerId(other.getId());playerCityRepository.saveAndFlush(branch);
        // 残留城市状态不能泄露，账号新手保护也不能伪装成道具护盾。
        player.setNewbieProtectionStartedAt(time.get());playerRepository.saveAndFlush(player);
        var result = buffs.snapshot(player.getId());
        assertEquals(1,result.cities().size());assertEquals(1,result.effects().size());
        assertEquals(main.getId(),result.effects().get(0).cityId());
        var mainState = states.findByPlayerIdAndCitySlot(player.getId(),0).orElseThrow();
        mainState.setShieldUntil(0L);states.saveAndFlush(mainState);
        assertTrue(buffs.snapshot(player.getId()).effects().isEmpty());
    }

    @Test void endpointRequiresLoginAndIgnoresRequestedPlayerId() throws Exception {
        cityState(0,time.get()+1000,0,0);cityState(1,time.get()+1000,0,0);
        var other=createTestPlayer("buff-intruder",30);
        http.perform(get("/api/game/item-buffs")).andExpect(status().isUnauthorized());
        String token=jwt.generateToken(player.getUsername(),player.getId(),player.getAuthVersion());
        http.perform(get("/api/game/item-buffs").param("playerId",other.getId().toString())
                .header("Authorization","Bearer "+token).header("X-City-Id",branch.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.playerId").value(player.getId()))
                .andExpect(jsonPath("$.effects.length()").value(2)).andExpect(jsonPath("$.cities.length()").value(2));
    }
}
