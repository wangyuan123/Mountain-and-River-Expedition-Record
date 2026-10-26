package com.wargame;

import com.wargame.model.constants.NewbieProtection;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.security.GameAccessException;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.GuildRepository;
import com.wargame.service.WorldMapService;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static com.wargame.model.constants.NewbieProtection.DAY_MS;
import static org.junit.jupiter.api.Assertions.*;

class NewbieProtectionIntegrationTest extends BaseServiceTest {
    @Autowired private WorldMapService maps;
    @Autowired private GuildMemberRepository members;
    @Autowired private GuildRepository guilds;
    private Player newcomer;
    private Player defender;
    private PlayerCity city;
    private WildTile wild;

    @BeforeEach
    void setup() {
        newcomer = createTestPlayer("newcomer", 30);
        newcomer.setNewbieProtectionStartedAt(System.currentTimeMillis());
        defender = createTestPlayer("veteran", 30);
        long worldId = createTestWorld().getId();
        city = new PlayerCity();
        city.setWorldId(worldId);
        city.setOwnerId(defender.getId());
        city.setName("目标城市");
        city.setX(20);
        city.setY(20);
        city.setCitySlot(0);
        city = playerCityRepository.save(city);
        wild = createWildTile(worldId, "forest", 15, 15, 1, Map.of("infantry", 10), 0);
        createArmyUnit(newcomer.getId(), "infantry", 100);
        createArmyUnit(newcomer.getId(), "recon_car", 2);
    }

    @Test
    void firstEntryStartsOnceAndExposesAccountAndCityDeadlines() {
        newcomer.setNewbieProtectionStartedAt(null);
        playerRepository.saveAndFlush(newcomer);
        gameStateService.enterGame(newcomer.getId());
        Long started = playerRepository.findById(newcomer.getId()).orElseThrow().getNewbieProtectionStartedAt();
        assertNotNull(started);
        gameStateService.enterGame(newcomer.getId());
        assertEquals(started, newcomer.getNewbieProtectionStartedAt());
        var state = gameStateService.getGameState(newcomer.getId());
        assertEquals(started + 7 * DAY_MS, ((Map<?, ?>) state.get("player")).get("newbieProtectionUntil"));
        assertEquals(started + 7 * DAY_MS, ((Map<?, ?>) state.get("cityState")).get("newbieProtectionUntil"));
        gameStateService.enterGame(defender.getId());
        assertEquals(0L, defender.getNewbieProtectionStartedAt());
    }

    @Test
    void declareWarRequiresConfirmationThenPermanentlyExits() {
        var error = assertThrows(GameAccessException.class,
                () -> worldService.declareWar(newcomer.getId(), city.getId()));
        assertEquals("NEWBIE_PROTECTION_CONFIRM_REQUIRED", error.getCode());
        assertNull(newcomer.getWarAgainstId());
        assertFalse(newcomer.isNewbieProtectionEnded());
        assertEquals(true, worldService.declareWar(newcomer.getId(), city.getId(), true).get("success"));
        assertTrue(newcomer.isNewbieProtectionEnded());
        assertEquals(defender.getId(), newcomer.getWarAgainstId());
    }

    @Test
    void failedConfirmedWarDoesNotConsumeProtection() {
        defender.setNewbieProtectionStartedAt(System.currentTimeMillis());
        assertEquals(false, worldService.declareWar(newcomer.getId(), city.getId(), true).get("success"));
        assertFalse(newcomer.isNewbieProtectionEnded());
        assertNull(newcomer.getWarAgainstId());
    }

    @Test
    void scoutingWithoutConfirmationDoesNotDeductArmyOrSupplies() {
        long oilBefore = getResources(newcomer.getId()).getOil();
        var error = assertThrows(GameAccessException.class,
                () -> marchService.createDispatch(newcomer.getId(), request("player", city.getId(), "scout", false)));
        assertEquals("NEWBIE_PROTECTION_CONFIRM_REQUIRED", error.getCode());
        assertEquals(2, armyUnitRepository.findByPlayerIdAndType(newcomer.getId(), "recon_car").get(0).getCount());
        assertEquals(oilBefore, getResources(newcomer.getId()).getOil());
        assertTrue(marchRepository.findByPlayerId(newcomer.getId()).isEmpty());
        assertFalse(newcomer.isNewbieProtectionEnded());
    }

    @Test
    void confirmedScoutingExitsAndInvalidConfirmedDispatchPreservesProtection() {
        assertNotNull(marchService.createDispatch(newcomer.getId(), request("player", city.getId(), "scout", true)).getId());
        assertTrue(newcomer.isNewbieProtectionEnded());
    }

    @Test
    void insufficientSuppliesWithConfirmationDoesNotConsumeProtection() {
        giveResources(newcomer.getId(), 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class,
                () -> marchService.createDispatch(newcomer.getId(), request("player", city.getId(), "scout", true)));
        assertFalse(newcomer.isNewbieProtectionEnded());
        assertTrue(marchRepository.findByPlayerId(newcomer.getId()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"conquer", "plunder", "feint", "harass", "guerrilla", "scout"})
    void protectedDefenderBlocksEveryHostileActionAndEveryCity(String action) {
        defender.setNewbieProtectionStartedAt(System.currentTimeMillis());
        city.setCitySlot(1);
        playerCityRepository.save(city);
        assertThrows(IllegalArgumentException.class,
                () -> marchService.createDispatch(newcomer.getId(), request("player", city.getId(), action, true)));
        assertFalse(newcomer.isNewbieProtectionEnded());
        var detail = maps.target(newcomer.getId(), "player", city.getId());
        assertEquals("shield", detail.get("cityState"));
        assertEquals(defender.getNewbieProtectionStartedAt() + 7 * DAY_MS, detail.get("shieldUntil"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"conquer", "scout"})
    void protectionAtArrivalReturnsArmyAndCargoWithoutBattle(String action) {
        defender.setNewbieProtectionStartedAt(System.currentTimeMillis());
        newcomer.setNewbieProtectionEnded(true);
        long now = System.currentTimeMillis();
        March march = createMarch(newcomer.getId(), "player", city.getId().toString(), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 10), action, now - 60_000L, now, false, false);
        march.setCarryRes(JsonUtil.toJson(Map.of("food", 100)));
        marchService.processMarches(newcomer.getId(), now);
        assertTrue(march.getReturning());
        assertNull(march.getBattleId());
        assertEquals(Map.of("food", 100), JsonUtil.parseIntMap(march.getCarryRes()));
        assertTrue(scoutReportRepository.findByPlayerId(newcomer.getId()).isEmpty());
    }

    @Test
    void pveAndConstructionKeepProtectionAndTickExtendsAtSevenDays() {
        giveResources(newcomer.getId(), 100000, 100000, 100000, 100000, 100000);
        var construction = buildService.upgrade(newcomer.getId(), "command", 0);
        assertEquals(true, construction.get("success"), construction.toString());
        assertNotNull(marchService.createDispatch(newcomer.getId(), request("wild", wild.getId(), "conquer", false)).getId());
        assertFalse(newcomer.isNewbieProtectionEnded());
        long started = newcomer.getNewbieProtectionStartedAt();
        var tick = tickService.buildStateChanges(newcomer.getId(), getResources(newcomer.getId()),
                started + 7 * DAY_MS, List.of(), List.of(), 100, 100, 100, 70, 0, 30);
        assertEquals(started + 14 * DAY_MS, tick.get("newbieProtectionUntil"));
        newcomer.setNewbieProtectionEnded(true);
        tick = tickService.buildStateChanges(newcomer.getId(), getResources(newcomer.getId()),
                started + 7 * DAY_MS, List.of(), List.of(), 100, 100, 100, 70, 0, 30);
        assertEquals(0L, tick.get("newbieProtectionUntil"));
    }

    @Test
    void scoutingWildClaimedDuringTravelReturnsWithoutExposingProtectedPlayerToWar() {
        March march = marchService.createDispatch(newcomer.getId(), request("wild", wild.getId(), "scout", false));
        wild.setOccupied(true);
        wild.setOccupiedBy(defender.getId());
        wildTileRepository.save(wild);
        marchService.processMarches(newcomer.getId(), march.getArriveAt());
        assertTrue(march.getReturning());
        assertFalse(newcomer.isNewbieProtectionEnded());
        assertTrue(scoutReportRepository.findByPlayerId(newcomer.getId()).isEmpty());
    }

    @Test
    void occupiedWildScoutingAndQuickScoutCannotBypassConfirmation() {
        wild.setOccupied(true);
        wild.setOccupiedBy(defender.getId());
        wildTileRepository.save(wild);
        assertThrows(GameAccessException.class,
                () -> marchService.createDefaultReconDispatch(newcomer.getId(), "wild", wild.getId()));
        assertFalse(newcomer.isNewbieProtectionEnded());
    }

    @Test
    void reinforcementRequiresConsentWhileTransportAndOwnCityRebaseKeepProtection() {
        var world = worldMapRepository.findById(city.getWorldId()).orElseThrow();
        world.setTerrainData("0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE));
        var guild = new Guild();
        // 接收许可已满足后，仍须单独确认退出新手保护。
        city.setAcceptGarrison(true);
        guild.setName("新手支援军团");
        guild.setLeaderPlayerId(newcomer.getId());
        guild.setCreatedAt(1L);
        guilds.save(guild);
        members.save(new GuildMember(null, guild.getId(), newcomer.getId(), "leader", 1L));
        members.save(new GuildMember(null, guild.getId(), defender.getId(), "member", 1L));
        createArmyUnit(newcomer.getId(), "truck", 2);
        giveResources(newcomer.getId(), 100000, 100000, 100000, 100000, 100000);
        assertThrows(GameAccessException.class,
                () -> marchService.createDispatch(newcomer.getId(), request("player", city.getId(), "reinforce", false)));
        assertFalse(newcomer.isNewbieProtectionEnded());
        March transport = marchService.createDispatch(newcomer.getId(), new DispatchRequest("player", city.getId(),
                "transport", Map.of("truck", 1), null, Map.of("food", 10)));
        assertNotNull(transport.getId());
        assertFalse(newcomer.isNewbieProtectionEnded());
        marchRepository.delete(transport);

        var branch = new PlayerCity();
        branch.setWorldId(city.getWorldId());
        branch.setOwnerId(newcomer.getId());
        branch.setName("新手分城");
        branch.setCitySlot(1);
        branch.setX(12);
        branch.setY(10);
        playerCityRepository.save(branch);
        March rebase = marchService.createDispatch(newcomer.getId(), request("player", branch.getId(), "rebase", false));
        assertNotNull(rebase.getId());
        assertFalse(newcomer.isNewbieProtectionEnded());
        marchRepository.delete(rebase);

        assertNotNull(marchService.createDispatch(newcomer.getId(), request("player", city.getId(), "reinforce", true)).getId());
        assertTrue(newcomer.isNewbieProtectionEnded());
    }

    @Test
    void itemShieldRemainsIndependentAndDoesNotGetConsumedByConfirmedAction() {
        var shield = createCityState(newcomer.getId(), "peace", System.currentTimeMillis() + DAY_MS, 0L);
        assertThrows(IllegalArgumentException.class,
                () -> marchService.createDispatch(newcomer.getId(), request("player", city.getId(), "scout", true)));
        assertFalse(newcomer.isNewbieProtectionEnded());
        assertTrue(shield.getShieldUntil() > System.currentTimeMillis());
        assertNotNull(marchService.createDispatch(newcomer.getId(), request("wild", wild.getId(), "conquer", false)).getId());
    }

    private DispatchRequest request(String kind, Long targetId, String action, boolean confirm) {
        return new DispatchRequest(kind, targetId, action,
                "scout".equals(action) ? Map.of("recon_car", 1) : Map.of("infantry", 10),
                null, null, null, null, null, null, confirm);
    }
}
