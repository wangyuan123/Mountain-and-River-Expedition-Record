package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.service.bot.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import com.wargame.service.CityScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.jdbc.Sql;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Sql(statements = {"DROP TABLE IF EXISTS bot_action_logs", "DROP TABLE IF EXISTS bot_profiles"})
@Sql(scripts = "/db/migration/V130__bot_players.sql")
class BotIntegrationTest extends BaseServiceTest {
    @Autowired private BotService bots;
    @Autowired private BotStore store;
    @Autowired private CityScope cityScope;
    @SpyBean private BotPlanner planner;
    private Player player;
    private final BotStore.Settings settings = new BotStore.Settings(true, false, 0, 45, 35, 2, 500);

    @BeforeEach
    void setUp() {
        player = createTestPlayer("bot-test", 30);
        WorldMap world = createTestWorld();
        PlayerCity city = new PlayerCity();
        city.setOwnerId(player.getId()); city.setCitySlot(0); city.setWorldId(world.getId());
        city.setName("Bot City"); city.setX(10); city.setY(10);
        playerCityRepository.saveAndFlush(city);
        playerRepository.flush();
        createBuilding(player.getId(), "command", 1);
        doReturn(BotAction.simple("BUILD", "farm", "test")).when(planner).plan(any(), any(), any(), any(), anyLong());
    }

    @Test
    void requiresConversionAndUsesRealConstructionCost() {
        assertThrows(IllegalArgumentException.class, () -> bots.configure(player.getId(), settings, false, "test", null));
        bots.configure(player.getId(), settings, true, "test", null);
        assertTrue(playerRepository.findById(player.getId()).orElseThrow().getIsBot());
        long steel = resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getSteel();
        var result = bots.run(player.getId(), false);
        assertTrue(result.success(), result.reason());
        assertEquals("BUILD", result.action());
        assertEquals(steel - 80, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getSteel());
        assertEquals(1, constructionRepository.findByPlayerId(player.getId()).size());
        assertEquals(1, store.find(player.getId(), false).orElseThrow().actionCount());
        bots.run(player.getId(), false);
        assertEquals(1, constructionRepository.findByPlayerId(player.getId()).size());
        verify(planner, times(1)).plan(any(), any(), any(), any(), anyLong());
    }

    @Test
    void forceDoesNotBypassPause() {
        bots.configure(player.getId(), new BotStore.Settings(false, false, 0, 45, 35, 2, 500), true, "test", null);
        assertEquals("WAIT", bots.run(player.getId(), true).action());
        verifyNoInteractions(planner);
        assertTrue(store.logs(player.getId(), 10).isEmpty());
    }

    @Test
    void bannedAccountIsPausedBeforePlanning() {
        bots.configure(player.getId(), settings, true, "test", null);
        player = playerRepository.findById(player.getId()).orElseThrow();
        player.setBanStatus("BANNED"); player.setBannedUntil(0L);
        playerRepository.saveAndFlush(player);
        assertEquals("WAIT", bots.run(player.getId(), true).action());
        assertFalse(store.find(player.getId(), false).orElseThrow().settings().enabled());
        verifyNoInteractions(planner);
    }

    @Test
    void executionRejectsHumanTargetsEvenWhenPlannerRequestsThem() {
        bots.configure(player.getId(), settings, true, "test", null);
        Player human = createTestPlayer("human", 30);
        PlayerCity target = new PlayerCity();
        target.setOwnerId(human.getId()); target.setCitySlot(0);
        target.setWorldId(worldMapRepository.findFirstByOrderByIdAsc().orElseThrow().getId());
        target.setName("Human City"); target.setX(20); target.setY(20);
        playerCityRepository.saveAndFlush(target);
        doReturn(BotAction.target("ATTACK", "player", target.getId(), Map.of("infantry", 10), "test")).when(planner).plan(any(), any(), any(), any(), anyLong());
        assertThrows(RuntimeException.class, () -> bots.run(player.getId(), true));
        assertTrue(marchRepository.findByPlayerId(player.getId()).isEmpty());
        assertEquals(0, store.find(player.getId(), false).orElseThrow().actionCount());
    }

    @Test
    void developsFromStarterEconomyThroughReconUnlock() throws Exception {
        createBuilding(player.getId(), "farm", 1);
        createBuilding(player.getId(), "refinery", 1);
        createBuilding(player.getId(), "house", 1);
        createArmyUnit(player.getId(), "infantry", 50);
        player.setCivilianPopulation(150);
        playerRepository.saveAndFlush(player);
        giveResources(player.getId(), 2000, 3000, 1000, 200, 1000);
        doCallRealMethod().when(planner).plan(any(), any(), any(), any(), anyLong());
        AtomicLong time = new AtomicLong(GameClock.now());
        try (var ignored = GameClock.use(time::get)) {
            bots.configure(player.getId(), settings, true, "test", null);
            // 一级侦察科研现需 6000 黄金，旧四小时窗口不足以靠初始人口税收积攒；不额外注资。
            long developmentDeadline = time.get() + Duration.ofHours(24).toMillis();
            while (time.get() < developmentDeadline) {
                var result = bots.run(player.getId(), false);
                assertTrue(result.success(), result.reason());
                time.addAndGet(120_000);
                tickService.tick(player.getId());
                if (armyUnitRepository.findByPlayerIdAndType(player.getId(), "recon_car").stream().anyMatch(unit -> unit.getCount() >= 3)) break;
            }
        }
        assertTrue(armyUnitRepository.findByPlayerIdAndType(player.getId(), "recon_car").stream().anyMatch(unit -> unit.getCount() >= 3),
                () -> "Bot 应在24小时模拟发展内自主解锁并生产3辆侦查车，最近记录：" + store.logs(player.getId(), 20)
                        + "；建筑：" + buildingRepository.findByPlayerId(player.getId())
                        + "；资源：" + resourcesRepository.findByPlayerId(player.getId())
                        + "；科技：" + technologyRepository.findByPlayerId(player.getId()));
    }

    @Test
    void gathersHarvestsAndReturnsCargoExactlyOnce() throws Exception {
        var tile = createWildTile(worldMapRepository.findFirstByOrderByIdAsc().orElseThrow().getId(), "grainfield", 15, 15, 1, Map.of(), 1000);
        tile.setOccupied(true); tile.setOccupiedBy(player.getId()); tile.setOccupiedCitySlot(0);
        wildTileRepository.saveAndFlush(tile);
        AtomicLong time = new AtomicLong(GameClock.now());
        try (var ignored = GameClock.use(time::get)) {
            March station = createMarch(player.getId(), "wild", tile.getId().toString(), "粮田", 10, 10, 15, 15,
                    Map.of("truck", 1), "station", time.get() - 60_000, time.get() - 30_000, false, false);
            station.setRouteMode("land"); station.setRouteData("[[10,10],[15,10],[15,15]]");
            marchRepository.saveAndFlush(station);
            marchService.processMarches(player.getId(), time.get());
            doCallRealMethod().when(planner).plan(any(), any(), any(), any(), anyLong());
            bots.configure(player.getId(), settings, true, "test", null);
            long food = getResources(player.getId()).getFood();
            assertEquals("GATHER", bots.run(player.getId(), true).action());
            assertEquals(food, getResources(player.getId()).getFood());
            time.set(tile.getGatherEndAt());
            assertEquals("HARVEST", bots.run(player.getId(), true).action());
            assertEquals(food, getResources(player.getId()).getFood());
            assertEquals("RECALL_WILD", bots.run(player.getId(), true).action());
            assertEquals(food, getResources(player.getId()).getFood());
            March returning = marchRepository.findByPlayerId(player.getId()).get(0);
            time.set(returning.getArriveAt());
            marchService.processMarches(player.getId(), time.get());
            marchService.processMarches(player.getId(), time.get() + 1000);
            assertEquals(food + 500, getResources(player.getId()).getFood());
            assertEquals(1, armyUnitRepository.findByPlayerIdAndType(player.getId(), "truck").get(0).getCount());
            assertTrue(marchRepository.findByPlayerId(player.getId()).isEmpty());
        }
    }

    @Test
    void dispatchesReconThenAttacksUsingTheArrivedReport() throws Exception {
        var tile = createWildTile(worldMapRepository.findFirstByOrderByIdAsc().orElseThrow().getId(), "grainfield", 12, 10, 1, Map.of(), 1000);
        createArmyUnit(player.getId(), "infantry", 100);
        createArmyUnit(player.getId(), "recon_car", 3);
        createTechnology(player.getId(), "recon_level", 1);
        AtomicLong time = new AtomicLong(GameClock.now());
        try (var clock = GameClock.use(time::get); var scope = cityScope.enter(player.getId(), 0)) {
            doCallRealMethod().when(planner).plan(any(), any(), any(), any(), anyLong());
            bots.configure(player.getId(), settings, true, "test", null);
            assertEquals("SCOUT", bots.run(player.getId(), true).action());
            March scout = marchRepository.findByPlayerId(player.getId()).get(0);
            assertEquals(Map.of("recon_car", 1), JsonUtil.parseIntMap(scout.getArmy()));
            assertTrue(scoutReportRepository.findByPlayerId(player.getId()).isEmpty());
            time.set(scout.getArriveAt());
            marchService.processMarches(player.getId(), time.get());
            assertFalse(scoutReportRepository.findByPlayerId(player.getId()).isEmpty());
            time.set(scout.getArriveAt());
            marchService.processMarches(player.getId(), time.get());
            var next = planner.plan(new BotStore.Profile(player.getId(), settings, 0, 0, 0, "", 0),
                    playerRepository.findById(player.getId()).orElseThrow(), playerCityRepository.findByOwnerIdAndCitySlot(player.getId(), 0).orElseThrow(),
                    getResources(player.getId()), time.get());
            assertEquals("ATTACK", next.type());
            assertEquals(tile.getId(), next.targetId());
            doReturn(next).when(planner).plan(any(), any(), any(), any(), anyLong());
            assertTrue(bots.run(player.getId(), true).success());
            March attack = marchRepository.findByPlayerId(player.getId()).get(0);
            assertEquals("conquer", attack.getAction());
            assertEquals("occupy", attack.getConquerMode());
            assertEquals("auto", attack.getBattleMode());
            assertEquals(35, armyUnitRepository.findByPlayerIdAndType(player.getId(), "infantry").get(0).getCount());
        }
    }
}
