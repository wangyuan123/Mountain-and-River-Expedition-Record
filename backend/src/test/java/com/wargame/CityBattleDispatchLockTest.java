package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.security.GameAccessException;
import com.wargame.service.CityBattleLockService;
import com.wargame.service.CityScope;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.GameClock;
import com.wargame.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CityBattleDispatchLockTest extends BaseServiceTest {
    @Autowired CityScope scope;
    @Autowired CityBattleLockService lock;
    @Autowired BattleSessionRepository battles;
    @Autowired MockMvc http;
    @Autowired JwtUtil jwt;
    private Player attacker, defender;
    private PlayerCity home;
    private WildTile wild;
    private WorldMap world;
    private March attack;

    @BeforeEach
    void prepare() {
        attacker = createTestPlayer("lock-attacker", 30);
        defender = createTestPlayer("lock-defender", 30);
        world = createTestWorld();
        world.setTerrainData("0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE));
        worldMapRepository.save(world);
        city(attacker, 0, 10);
        home = city(defender, 0, 20);
        createArmyUnit(defender.getId(), "infantry", 1000);
        createArmyUnit(defender.getId(), "scout", 10);
        wild = createWildTile(world.getId(), "forest", 30, 10, 1, Map.of("infantry", 1), 1000);
        giveResources(defender.getId(), 100000, 100000, 100000, 100000, 100000);
        long now = GameClock.now();
        attacker.setWarAgainstId(defender.getId());
        attacker.setWarAt(now - 1000);
        attacker.setWarEndAt(now + 3600000);
        playerRepository.save(attacker);
        attack = createMarch(attacker.getId(), "player", home.getId().toString(), home.getName(),
                10, 10, 20, 10, Map.of("infantry", 1), "plunder", now - 60000, now + 60000, false, false);
    }

    private PlayerCity city(Player owner, int slot, int x) {
        PlayerCity city = new PlayerCity();
        city.setWorldId(world.getId()); city.setName("city-" + owner.getId() + "-" + slot);
        city.setOwnerId(owner.getId()); city.setCitySlot(slot); city.setX(x); city.setY(10); city.setLevel(1);
        return playerCityRepository.saveAndFlush(city);
    }

    private void startBattle() {
        attack.setArriveAt(GameClock.now() - 1);
        marchRepository.saveAndFlush(attack);
        marchService.processMarches(attacker.getId(), GameClock.now());
        assertTrue(lock.locked(defender.getId()));
        assertNotNull(battles.findByMarchId(attack.getId()).orElseThrow());
    }

    private DispatchRequest request(String kind, String action) {
        return new DispatchRequest(kind, wild.getId(), action, Map.of("scout", 1), null, null);
    }

    @ParameterizedTest
    @CsvSource({"wild,conquer", "wild,plunder", "wild,feint", "wild,harass", "wild,guerrilla", "wild,scout",
            "wild,station", "player,transport", "player,rebase", "player,reinforce",
            "incoming_march,intercept_scout", "incoming_scout,intercept_scout"})
    void everyDepartureIsBlockedWithoutSpendingTroopsOrResources(String kind, String action) {
        startBattle();
        long oil = getResources(defender.getId()).getOil();
        long count = marchRepository.count();
        var error = assertThrows(GameAccessException.class, () -> marchService.createDispatch(defender.getId(), request(kind, action)));
        assertEquals("CITY_IN_BATTLE", error.getCode());
        assertEquals(CityBattleLockService.MESSAGE, error.getMessage());
        assertEquals(409, error.getStatus());
        assertEquals(count, marchRepository.count());
        assertEquals(oil, getResources(defender.getId()).getOil());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(defender.getId(), 0, "scout").get(0).getCount());
        assertEquals(1000, armyUnitRepository.findByPlayerIdAndCitySlotAndType(defender.getId(), 0, "infantry").get(0).getCount());
        assertEquals(true, gameStateService.getGameState(defender.getId()).get("cityBattleLocked"));
    }

    @Test
    void shortcutsAndRoutePreviewCannotBypassTheLock() {
        startBattle();
        assertEquals("CITY_IN_BATTLE", assertThrows(GameAccessException.class,
                () -> marchService.createDefaultReconDispatch(defender.getId(), "wild", wild.getId())).getCode());
        assertEquals("CITY_IN_BATTLE", assertThrows(GameAccessException.class,
                () -> marchService.interceptScout(defender.getId(), request("incoming_march", "intercept_scout"))).getCode());
        assertEquals("CITY_IN_BATTLE", assertThrows(GameAccessException.class,
                () -> marchService.previewRoute(defender.getId(), request("wild", "scout"))).getCode());
        assertEquals("CITY_IN_BATTLE", assertThrows(GameAccessException.class,
                () -> worldService.scout(defender.getId(), 0, "wild")).getCode());
    }

    @Test
    void directHttpCallsReceiveTheBattleLockMessage() throws Exception {
        startBattle();
        String token = jwt.generateToken(defender.getUsername(), defender.getId(), defender.getAuthVersion());
        for (String endpoint : new String[] {"/api/game/world/dispatch", "/api/game/world/route"}) {
            http.perform(post(endpoint).header("Authorization", "Bearer " + token).contentType("application/json")
                    .content("{\"targetKind\":\"wild\",\"targetId\":" + wild.getId() + ",\"action\":\"scout\",\"army\":{\"scout\":1}}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CITY_IN_BATTLE"))
                    .andExpect(jsonPath("$.error").value(CityBattleLockService.MESSAGE));
        }
        http.perform(post("/api/game/wild/scout").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"wildTileId\":" + wild.getId() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CITY_IN_BATTLE"));
        assertEquals(1, marchRepository.count());
    }

    @Test
    void incomingMarchDoesNotLockCityAndFinishedBattleAutomaticallyUnlocksIt() {
        assertFalse(lock.locked(defender.getId()));
        assertEquals(false, tickService.pushStateChanges(defender.getId()).get("cityBattleLocked"));
        March outbound = marchService.createDispatch(defender.getId(), request("wild", "scout"));
        assertNotNull(outbound.getId());
        startBattle();
        assertEquals(true, tickService.pushStateChanges(defender.getId()).get("cityBattleLocked"));
        assertFalse(marchRepository.findById(outbound.getId()).orElseThrow().getReturning());
        var session = battles.findByMarchId(attack.getId()).orElseThrow();
        long now = GameClock.now();
        for (int round = 0; round < 30 && battles.existsById(session.getId()); round++) {
            now += 15000;
            marchService.processTimedOutTacticalBattle(session.getId(), now);
        }
        assertFalse(battles.existsById(session.getId()));
        assertFalse(lock.locked(defender.getId()));
        assertEquals(false, tickService.pushStateChanges(defender.getId()).get("cityBattleLocked"));
        assertEquals(false, gameStateService.getGameState(defender.getId()).get("cityBattleLocked"));
        assertNotNull(marchService.createDispatch(defender.getId(), request("wild", "scout")).getId());
    }

    @Test
    void otherCitiesAndTheAttackersOriginRemainFreeToDispatch() {
        startBattle();
        assertFalse(lock.locked(attacker.getId()));
        city(defender, 1, 40);
        try (var ignored = scope.enter(defender.getId(), 1)) {
            var unit = createArmyUnit(defender.getId(), "scout", 5);
            unit.setCitySlot(1); armyUnitRepository.saveAndFlush(unit);
            Resources resources = new Resources(); resources.setPlayerId(defender.getId()); resources.setCitySlot(1);
            resources.setOil(10000); resourcesRepository.saveAndFlush(resources);
            assertFalse(lock.locked(defender.getId()));
            assertEquals(false, gameStateService.getGameState(defender.getId()).get("cityBattleLocked"));
            assertNotNull(marchService.createDispatch(defender.getId(), request("wild", "scout")).getId());
        }
        assertTrue(lock.locked(defender.getId()));
    }

    @Test
    void besiegedSubcityIsLockedWithoutLockingTheMainCity() {
        PlayerCity subcity = city(defender, 1, 40);
        var unit = createArmyUnit(defender.getId(), "infantry", 50);
        unit.setCitySlot(1); armyUnitRepository.saveAndFlush(unit);
        attack.setTargetId(subcity.getId().toString()); attack.setTargetName(subcity.getName());
        attack.setTargetX(subcity.getX()); attack.setTargetY(subcity.getY());
        try (var ignored = scope.enter(defender.getId(), 1)) {
            startBattle();
            var error = assertThrows(GameAccessException.class,
                    () -> marchService.createDispatch(defender.getId(), request("wild", "scout")));
            assertEquals("CITY_IN_BATTLE", error.getCode());
        }
        assertFalse(lock.locked(defender.getId()));
        assertNotNull(marchService.createDispatch(defender.getId(), request("wild", "scout")).getId());
    }
}
