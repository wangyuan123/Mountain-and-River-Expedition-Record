package com.wargame.service.bot;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BotPlannerTest {
    @Mock private BuildingRepository buildings;
    @Mock private ConstructionRepository constructions;
    @Mock private ArmyUnitRepository armies;
    @Mock private ArmyProductionQueueRepository production;
    @Mock private TechResearchQueueRepository research;
    @Mock private WildTileRepository wilds;
    @Mock private MarchRepository marches;
    @Mock private ScoutReportRepository reports;
    @Mock private PlayerRepository players;
    @Mock private BuildService build;
    @Mock private TechService tech;
    @Mock private TickService ticks;
    @Mock private PrerequisiteService prerequisites;
    @Mock private UnitUnlockService unlocks;
    @Mock private WorldViewService worldView;
    @Mock private WorldService world;
    @Mock private MarchService marchService;
    @Mock private WildOccupationService occupation;
    @Mock private CityScope cityScope;
    @Mock private PlayerItemRepository items;
    @InjectMocks private BotPlanner planner;
    private final long now = 1_000_000;
    private Player player;
    private PlayerCity city;
    private Resources wallet;
    private BotStore.Profile profile;

    @BeforeEach
    void setUp() {
        player = new Player(); player.setId(1L);
        city = new PlayerCity(); city.setId(1L); city.setOwnerId(1L); city.setCitySlot(0); city.setX(10); city.setY(10);
        wallet = new Resources(); wallet.setFood(200); wallet.setSteel(1000); wallet.setOil(1000); wallet.setRare(200); wallet.setGold(1000);
        profile = new BotStore.Profile(1, new BotStore.Settings(true, true, 0, 45, 35, 2, 500), 0, 0, 0, "", 0);
        ArmyUnit infantry = new ArmyUnit(); infantry.setType("infantry"); infantry.setCount(100);
        ArmyUnit recon = new ArmyUnit(); recon.setType("recon_car"); recon.setCount(3);
        when(armies.findByPlayerIdAndCitySlot(1L, 0)).thenReturn(List.of(infantry, recon));
        when(constructions.findByPlayerIdAndCitySlot(1L, 0)).thenReturn(List.of(new Construction(), new Construction(), new Construction()));
        when(research.findByPlayerIdOrderByStartedAtAscIdAsc(1L)).thenReturn(List.of(new TechResearchQueue()));
        when(ticks.foodPerHour(1L)).thenReturn(200L);
        when(world.calcScanRadius(1L)).thenReturn(3);
        when(marchService.getMaxMarches(1L, 0)).thenReturn(5);
        when(worldView.getWorld(eq(player), anyInt(), anyInt(), anyInt())).thenReturn(Map.of("bandits", List.of(target())));
    }

    @Test
    void scoutsUnknownDefendersWithoutUsingMapArmy() {
        assertEquals("SCOUT", plan().type());
        assertEquals(Map.of("recon_car", 1), plan().army());
    }

    @Test
    void attacksOnlyWithFreshPersonalReport() {
        evidence(Map.of("targetKind", "bandit", "army", Map.of("infantry", 10)), now);
        assertEquals("ATTACK", plan().type());
        assertEquals(Map.of("infantry", 65), plan().army());
        evidence(Map.of("targetKind", "bandit", "army", Map.of("infantry", 10)), now - 600_001);
        assertEquals("SCOUT", plan().type());
    }

    @Test
    void missingArmyInFailedReportIsNotAnEmptyGarrison() {
        evidence(Map.of("targetKind", "bandit", "success", false), now);
        assertEquals("SCOUT", plan().type());
        evidence(Map.of("targetKind", "bandit", "army", Map.of("infantry", 1000)), now);
        assertEquals("WAIT", plan().type());
    }

    @Test
    void avoidsDuplicateTargetsAndHonorsCooldown() {
        March march = new March(); march.setTargetKind("bandit"); march.setTargetId("7"); march.setArmy("{}");
        when(marches.findByPlayerIdAndCitySlot(1L, 0)).thenReturn(List.of(march));
        assertEquals("WAIT", plan().type());
        when(marches.findByPlayerIdAndCitySlot(1L, 0)).thenReturn(List.of());
        profile = new BotStore.Profile(1, profile.settings(), 0, 0, 1, "bandit:7", now + 300_000);
        assertEquals("WAIT", plan().type());
    }

    @Test
    void neverSelectsHumansAndSkipsProtectedBots() {
        Map<String, Object> target = new HashMap<>(target()); target.put("ownerId", 2L);
        when(worldView.getWorld(eq(player), anyInt(), anyInt(), anyInt())).thenReturn(Map.of("playerCities", List.of(target)));
        Player other = new Player(); other.setId(2L); other.setIsBot(false);
        when(players.findById(2L)).thenReturn(Optional.of(other));
        assertEquals("WAIT", plan().type());
        other.setIsBot(true); target.put("shieldUntil", now + 1000);
        assertEquals("WAIT", plan().type());
        target.remove("shieldUntil");
        assertEquals("SCOUT", plan().type());
    }

    @Test
    void declaresWarOnlyAfterReconAndWaitsForCountdown() {
        Map<String, Object> target = new HashMap<>(target()); target.put("ownerId", 2L);
        when(worldView.getWorld(eq(player), anyInt(), anyInt(), anyInt())).thenReturn(Map.of("playerCities", List.of(target)));
        Player other = new Player(); other.setId(2L); other.setIsBot(true);
        when(players.findById(2L)).thenReturn(Optional.of(other));
        evidence(Map.of("targetKind", "player", "targetPlayerId", 2, "army", Map.of("infantry", 10), "forts", Map.of(), "wallLevel", 0), now);
        when(world.getWarStatus(1L, 7L)).thenReturn(Map.of("phase", "peace"));
        assertEquals("DECLARE_WAR", plan().type());
        when(world.getWarStatus(1L, 7L)).thenReturn(Map.of("phase", "countdown"));
        assertEquals("WAIT", plan().type());
        when(world.getWarStatus(1L, 7L)).thenReturn(Map.of("phase", "combat"));
        assertEquals("ATTACK", plan().type());
    }

    @Test
    void harvestsThenRecallsAndReleasesDepletedTerritory() {
        WildTile tile = new WildTile(); tile.setId(9L); tile.setType("plain"); tile.setTotalRes(1000); tile.setMined(1000);
        tile.setGarrison("{\"infantry\":10}"); tile.setGathering(true); tile.setGatherEndAt(now);
        when(wilds.findByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(1L, 0)).thenReturn(List.of(tile));
        assertEquals("HARVEST", plan().type());
        tile.setGathering(false);
        assertEquals("RECALL_WILD", plan().type());
        tile.setGarrison("{}");
        assertEquals("ABANDON_WILD", plan().type());
    }

    private BotAction plan() { return planner.plan(profile, player, city, wallet, now); }

    private Map<String, Object> target() {
        return Map.of("id", 7L, "x", 11, "y", 10, "army", Map.of("infantry", 0));
    }

    private void evidence(Map<String, Object> data, long createdAt) {
        ScoutReport report = new ScoutReport(); report.setCreatedAt(createdAt); report.setData(JsonUtil.toJson(data));
        when(reports.findByPlayerIdAndTypeAndTargetXAndTargetYAndDetailExpiresAtGreaterThanOrderByCreatedAtDesc(eq(1L), eq("scout"), eq(11), eq(10), anyLong()))
                .thenReturn(List.of(report));
    }
}
