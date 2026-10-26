package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.service.CityScope;
import com.wargame.service.CityConquestService;
import com.wargame.service.WildOccupationService;
import com.wargame.service.WorldMapService;
import com.wargame.service.WorldViewService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证每城名额、真实行军结算、跨城隔离和名额释放。 */
class WildOccupationTest extends BaseServiceTest {
    @Autowired WildOccupationService occupation;
    @Autowired CityScope scope;
    @Autowired BattleSessionRepository sessions;
    @Autowired WorldMapService maps;
    @Autowired WorldViewService views;
    @Autowired CityConquestService conquest;
    private Player player;
    private Long worldId;
    private int nextPosition;

    @BeforeEach void setup() {
        player = createTestPlayer();
        worldId = createTestWorld().getId();
        nextPosition = 30;
        giveResources(player.getId(), 100000, 100000, 100000, 100000, 100000);
    }

    private WildTile tile(int citySlot, boolean occupied) {
        WildTile tile = createWildTile(worldId, "ironworks", nextPosition++, 20, 1, Map.of("infantry", 1), 10000);
        tile.setOccupied(occupied);
        tile.setOccupiedBy(occupied ? player.getId() : null);
        tile.setOccupiedCitySlot(citySlot);
        if (occupied) tile.setGarrison("{}");
        return wildTileRepository.save(tile);
    }

    private void owned(int slot, int count) {
        for (int i = 0; i < count; i++) tile(slot, true);
    }

    private Building hall(int slot, int level) {
        Building building = new Building(); building.setPlayerId(player.getId());
        building.setCitySlot(slot); building.setType("command"); building.setLevel(level);
        return buildingRepository.save(building);
    }

    private void branch() {
        PlayerCity city = new PlayerCity(); city.setWorldId(worldId); city.setOwnerId(player.getId());
        city.setCitySlot(1); city.setName("分城"); city.setX(12); city.setY(12); city.setReadyAt(0L);
        playerCityRepository.save(city);
        Resources stock = new Resources(); stock.setPlayerId(player.getId()); stock.setCitySlot(1);
        stock.setOil(100000); stock.setFood(100000); resourcesRepository.save(stock);
        ArmyUnit army = new ArmyUnit(); army.setPlayerId(player.getId()); army.setCitySlot(1);
        army.setType("infantry"); army.setCount(1000); armyUnitRepository.save(army);
    }

    @ParameterizedTest
    @CsvSource({"0,5", "1,6", "5,10", "10,15"})
    void limitUsesOnlyCompletedLocalCityHall(int level, int cap) {
        if (level > 0) hall(0, level);
        createBuilding(player.getId(), "staff", 10);
        hall(1, 10);
        createConstruction(player.getId(), "command", 10, 0L, Long.MAX_VALUE, null);
        assertEquals(cap, occupation.limit(player.getId(), 0).cap());
        assertEquals(15, occupation.limit(player.getId(), 1).cap());
    }

    @Test void stateAndOwnedEndpointsFollowCurrentCity() {
        hall(0, 1); hall(1, 10);
        owned(0, 2); owned(1, 3);
        branch();
        assertEquals(2L, occupation.limit(player.getId(), 0).ownedCount());
        try (var ignored = scope.enter(player.getId(), 1)) {
            Map<?, ?> wilds = (Map<?, ?>) gameStateService.getGameState(player.getId()).get("wilds");
            assertEquals(15, wilds.get("cap")); assertEquals(3L, wilds.get("ownedCount"));
            assertEquals(3, ((List<?>) wilds.get("owned")).size());
            assertEquals(3, worldService.getOwnedWildTiles(player.getId()).size());
        }
        assertEquals(2, worldService.getOwnedWildTiles(player.getId()).size());
    }

    @Test void fullCityRejectsBeforeConsumingTroopsAndFuel() {
        hall(0, 1); owned(0, 6); createArmyUnit(player.getId(), "infantry", 100);
        WildTile target = tile(0, false);
        long oil = resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getOil();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(
                player.getId(), new DispatchRequest("wild", target.getId(), "conquer", Map.of("infantry", 50), null, null)));
        assertTrue(error.getMessage().contains("6/6"));
        assertEquals(100, armyUnitRepository.findByPlayerIdAndCitySlotAndType(player.getId(), 0, "infantry").get(0).getCount());
        assertEquals(oil, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getOil());
        assertTrue(marchRepository.findByPlayerId(player.getId()).isEmpty());
    }

    @Test void levelTenCityCanClaimFifteenthButCannotClaimSixteenthWild() {
        hall(0, 10); owned(0, 14);
        assertTrue(occupation.claim(player.getId(), 0, tile(0, false)).claimed());
        WildTile extra = tile(0, false);
        assertFalse(occupation.claim(player.getId(), 0, extra).claimed());
        assertFalse(extra.getOccupied()); assertEquals(15L, occupation.limit(player.getId(), 0).ownedCount());
    }

    @Test void peacefulTransferMovesCityTerritoryAndPreservesReturningTroopOwnership() {
        branch(); hall(1, 2);
        Player recipient = createTestPlayer("recipient", 30);
        WildTile territory = tile(1, true);
        territory.setGarrison(JsonUtil.toJson(Map.of("infantry", 7)));
        territory.setGarrisonRoutes(JsonUtil.toJson(List.of(
                Map.of("playerId", player.getId(), "citySlot", 0, "fromX", 10, "fromY", 10,
                        "distance", 20, "duration", 1000, "army", Map.of("infantry", 4)),
                Map.of("playerId", player.getId(), "citySlot", 1, "fromX", 12, "fromY", 12,
                        "distance", 18, "duration", 1000, "army", Map.of("infantry", 3)))));
        WildTile safe = tile(0, true);
        PlayerCity city = playerCityRepository.findByOwnerIdAndCitySlot(player.getId(), 1).orElseThrow();
        int slot = conquest.peacefulTransfer(recipient.getId(), city, System.currentTimeMillis());
        assertEquals(recipient.getId(), territory.getOccupiedBy()); assertEquals(slot, territory.getOccupiedCitySlot());
        assertEquals(player.getId(), safe.getOccupiedBy()); assertEquals(0, safe.getOccupiedCitySlot());
        assertEquals("{}", territory.getGarrison());
        March oldOwnerReturn = marchRepository.findByPlayerIdAndCitySlot(player.getId(), 0).get(0);
        assertEquals(4, JsonUtil.parseIntMap(oldOwnerReturn.getArmy()).get("infantry"));
        March transferredReturn = marchRepository.findByPlayerIdAndCitySlot(recipient.getId(), slot).get(0);
        assertEquals(3, JsonUtil.parseIntMap(transferredReturn.getArmy()).get("infantry"));
        assertTrue(transferredReturn.getReturning());
        assertEquals(7, occupation.limit(recipient.getId(), slot).cap());
    }

    @Test void fullMainCityDoesNotConsumeBranchQuotaAndBattleUsesDepartureCity() {
        hall(0, 1); hall(1, 2); owned(0, 6); branch();
        WildTile target = tile(0, false);
        March march;
        try (var ignored = scope.enter(player.getId(), 1)) {
            march = marchService.createDispatch(player.getId(), new DispatchRequest("wild", target.getId(), "conquer",
                    Map.of("infantry", 100), null, null));
        }
        assertEquals(1, march.getCitySlot());
        // 结算按出发城市推进；此时玩家当前选择已经回到主城。
        try (var ignored = scope.enter(player.getId(), 1)) { marchService.processMarches(player.getId(), march.getArriveAt()); }
        assertEquals(1, wildTileRepository.findById(target.getId()).orElseThrow().getOccupiedCitySlot());
        assertEquals(6L, occupation.limit(player.getId(), 0).ownedCount());
        assertEquals(1L, occupation.limit(player.getId(), 1).ownedCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "manual"})
    void lastSlotIsRecheckedAfterBattleAndSurvivorsReturn(String mode) {
        hall(0, 1); owned(0, 5); createArmyUnit(player.getId(), "infantry", 100);
        WildTile target = tile(0, false);
        March march = marchService.createDispatch(player.getId(), new DispatchRequest("wild", target.getId(), "conquer",
                Map.of("infantry", 100), null, null, null, mode, "occupy"));
        owned(0, 1);
        long arrival = march.getArriveAt();
        marchService.processMarches(player.getId(), arrival);
        if ("manual".equals(mode)) {
            Long sessionId = sessions.findByMarchId(march.getId()).orElseThrow().getId();
            for (int round = 1; round <= 30 && sessions.findByMarchId(march.getId()).isPresent(); round++) {
                marchService.processTimedOutTacticalBattle(sessionId, arrival + round * 15000L);
            }
        }
        assertFalse(wildTileRepository.findById(target.getId()).orElseThrow().getOccupied());
        assertEquals(6L, occupation.limit(player.getId(), 0).ownedCount());
        assertTrue(marchRepository.findById(march.getId()).orElseThrow().getReturning());
        Map<String, Object> report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(player.getId()).get(0).getData());
        assertEquals(true, report.get("win")); assertEquals(false, report.get("wildConquered"));
        assertEquals("returning", report.get("troopDisposition"));
        assertTrue(report.get("wildOccupationReason").toString().contains("6/6"));
    }

    @Test void overCapLegacyHoldingsArePreservedAndAbandonReleasesLocalSlot() {
        hall(0, 1); owned(0, 7); owned(1, 1);
        assertThrows(IllegalArgumentException.class, () -> occupation.requireRoom(player.getId(), 0));
        List<WildTile> holdings = wildTileRepository.findByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(player.getId(), 0);
        assertEquals(true, worldService.abandonWild(player.getId(), holdings.get(0).getId()).get("success"));
        assertThrows(IllegalArgumentException.class, () -> occupation.requireRoom(player.getId(), 0));
        worldService.abandonWild(player.getId(), holdings.get(1).getId());
        assertDoesNotThrow(() -> occupation.requireRoom(player.getId(), 0));
        assertEquals(1L, occupation.limit(player.getId(), 1).ownedCount());
    }

    @Test void plunderStillWorksWhenCityIsFull() {
        hall(0, 1); owned(0, 6); createArmyUnit(player.getId(), "infantry", 100);
        WildTile target = tile(0, false);
        March march = marchService.createDispatch(player.getId(), new DispatchRequest("wild", target.getId(), "plunder",
                Map.of("infantry", 100), null, null));
        marchService.processMarches(player.getId(), march.getArriveAt());
        assertFalse(wildTileRepository.findById(target.getId()).orElseThrow().getOccupied());
        assertTrue(marchRepository.findById(march.getId()).orElseThrow().getReturning());
    }

    @Test void mapKeepsPlayerOwnershipAndExposesCityOnlyToOwner() {
        WildTile target = tile(1, true);
        assertEquals(1, maps.target(player.getId(), "wild", target.getId()).get("occupiedCitySlot"));
        Player other = createTestPlayer("other", 30);
        assertFalse(maps.target(other.getId(), "wild", target.getId()).containsKey("occupiedCitySlot"));
        Map<?, ?> world = views.getWorld(player.getId(), 10, 10, 0);
        Map<?, ?> viewed = (Map<?, ?>) ((List<?>) world.get("wildTiles")).get(0);
        assertEquals(true, viewed.get("occupied")); assertEquals(1, viewed.get("occupiedCitySlot"));
    }
}
