package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.CityScope;
import com.wargame.service.WorldTerrainService;
import com.wargame.service.quest.OnboardingService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 直接验证引导状态机与真实奖励变动，避免无关 PVP 启动配置阻断规则验证。 */
class OnboardingServiceUnitTest {
    PlayerGuideRepository guides;
    PlayerRepository players;
    BuildingRepository buildings;
    TechnologyRepository techs;
    ArmyUnitRepository armies;
    OfficerRepository officers;
    ResourcesRepository resources;
    ScoutReportRepository reports;
    MarchRepository marches;
    WildTileRepository wilds;
    GuildMemberRepository guilds;
    UnitLoadoutRepository loadouts;
    CityScope scope;
    com.wargame.service.MailService mail;
    OnboardingService onboarding;
    Map<String, PlayerGuide> progress;
    List<Technology> technologies;
    List<Building> facilities;
    List<ArmyUnit> troops;
    List<ScoutReport> intel;
    List<March> movements;
    List<WildTile> tiles;
    Resources balance;

    @BeforeEach
    void setup() {
        guides = mock(PlayerGuideRepository.class); players = mock(PlayerRepository.class);
        buildings = mock(BuildingRepository.class); techs = mock(TechnologyRepository.class);
        armies = mock(ArmyUnitRepository.class); officers = mock(OfficerRepository.class);
        resources = mock(ResourcesRepository.class); reports = mock(ScoutReportRepository.class);
        marches = mock(MarchRepository.class); wilds = mock(WildTileRepository.class);
        guilds = mock(GuildMemberRepository.class); loadouts = mock(UnitLoadoutRepository.class);
        scope = mock(CityScope.class);
        mail = mock(com.wargame.service.MailService.class);
        onboarding = new OnboardingService(guides, players, buildings, techs, armies, officers, resources,
                reports, marches, wilds, guilds, loadouts, mock(WorldTerrainService.class), scope, mail);
        org.springframework.test.util.ReflectionTestUtils.setField(onboarding, "populationCosts", mock(com.wargame.service.ArmyPopulationService.class));
        progress = new LinkedHashMap<>(); technologies = new ArrayList<>(); facilities = new ArrayList<>();
        troops = new ArrayList<>(); intel = new ArrayList<>(); movements = new ArrayList<>(); tiles = new ArrayList<>();
        Player player = new Player(); player.setId(1L);
        balance = new Resources(); balance.setPlayerId(1L); balance.setCitySlot(0);
        balance.setFood(60000); balance.setSteel(60000); balance.setOil(60000); balance.setRare(60000); balance.setGold(60000); balance.setDiamond(0);
        when(players.lockById(1L)).thenReturn(Optional.of(player));
        when(guides.lockByPlayerId(1L)).thenAnswer(i -> new ArrayList<>(progress.values()));
        when(guides.save(any(PlayerGuide.class))).thenAnswer(i -> {
            PlayerGuide row = i.getArgument(0); progress.put(row.getStepId(), row); return row;
        });
        when(resources.findByPlayerIdAndCitySlot(1L, 0)).thenReturn(Optional.of(balance));
        when(buildings.findByPlayerIdAndCitySlotAndType(eq(1L), eq(0), anyString())).thenAnswer(i -> facilities.stream().filter(b -> b.getType().equals(i.getArgument(2))).toList());
        when(buildings.save(any(Building.class))).thenAnswer(i -> { Building b = i.getArgument(0); facilities.add(b); return b; });
        when(techs.findByPlayerId(1L)).thenReturn(technologies);
        when(techs.findByPlayerIdAndType(eq(1L), anyString())).thenAnswer(i -> technologies.stream().filter(t -> t.getType().equals(i.getArgument(1))).toList());
        when(techs.save(any(Technology.class))).thenAnswer(i -> { Technology t = i.getArgument(0); if (!technologies.contains(t)) technologies.add(t); return t; });
        when(armies.findByPlayerIdAndCitySlotAndType(eq(1L), eq(0), anyString())).thenAnswer(i -> troops.stream().filter(a -> a.getType().equals(i.getArgument(2))).toList());
        when(armies.save(any(ArmyUnit.class))).thenAnswer(i -> { ArmyUnit a = i.getArgument(0); if (!troops.contains(a)) troops.add(a); return a; });
        when(reports.findByPlayerIdAndPersistedRealAtGreaterThanEqual(eq(1L), anyLong())).thenAnswer(i -> intel.stream().filter(r -> r.getPersistedRealAt() >= (Long) i.getArgument(1)).toList());
        when(marches.findByPlayerId(1L)).thenReturn(movements);
        when(marches.findByPlayerIdAndCitySlot(1L, 0)).thenReturn(movements);
        when(wilds.findByOccupiedBy(1L)).thenReturn(tiles);
        when(guilds.findByPlayerId(1L)).thenReturn(Optional.empty());
        when(loadouts.findByPlayerId(1L)).thenReturn(List.of());
    }

    @Test
    void starterIsGivenOnceAndIsNotMistakenForCompletedActions() {
        onboarding.start(1L); onboarding.start(1L);
        assertEquals(List.of("base", "infantry", "recon_build", "recon_research", "recon_unit", "scout", "occupy", "report"), OnboardingService.OBJECTIVES.stream().map(OnboardingService.Objective::id).toList());
        assertEquals("base", current()); assertEquals(0L, onboarding.status(1L).get("completed"));
        assertEquals(1, facilities.size()); assertEquals("factory", facilities.get(0).getType());
        assertTrue(troops.stream().noneMatch(a -> "scout".equals(a.getType()) || "recon_car".equals(a.getType())));
        assertTrue(technologies.isEmpty(), "新手不再直接获赠侦察科技");
        assertEquals(1, troops.stream().filter(a -> "truck".equals(a.getType())).findFirst().orElseThrow().getCount());
        assertEquals("available", task("truck").get("status"), "赠送卡车不能冒充生产成果");
        assertEquals("locked", task("tech").get("status"));
        assertResources(60000);
        verifyNoInteractions(mail);
    }

    @Test
    void coreActionsCompleteOnFactsAndReportsWithoutGatheringOrReturnWait() {
        onboarding.start(1L); onboarding.pause(1L, true);
        onboarding.onEvent(1L, "BUILD_UPGRADE_DONE", "oilfield", 1);
        assertEquals("infantry", current()); assertResources(70000);
        onboarding.onEvent(1L, "BUILD_UPGRADE_DONE", "farm", 1); onboarding.claim(1L, "base");
        assertResources(70000);
        onboarding.onEvent(1L, "ARMY_RECRUIT", "infantry", 1); assertEquals("recon_build", current());
        for (Object[] entry : List.of(new Object[]{"command", 2}, new Object[]{"factory", 2}, new Object[]{"radar", 1}, new Object[]{"lab", 1})) {
            Building building = new Building(); building.setPlayerId(1L); building.setCitySlot(0);
            building.setType((String) entry[0]); building.setLevel((Integer) entry[1]); facilities.add(building);
        }
        assertEquals("recon_research", current());
        Technology recon = new Technology(); recon.setPlayerId(1L); recon.setType("recon_level"); recon.setLevel(1); technologies.add(recon);
        onboarding.onEvent(1L, "TECH_LEVEL", "recon_level", 1); assertEquals("recon_unit", current());
        onboarding.onEvent(1L, "ARMY_RECRUIT", "recon_car", 1); assertEquals("scout", current());
        March scout = new March(); scout.setAction("scout"); scout.setTargetKind("wild"); scout.setReturning(true); movements.add(scout);
        report("scout", Map.of("targetKind", "wild", "showCityInfo", true), false);
        assertEquals("occupy", current()); assertResources(80000);
        ScoutReport win = report("battle", Map.of("targetType", "wild", "win", true, "wildConquered", false), false);
        assertEquals("report", current()); assertResources(90000);
        win.setReadAt(System.currentTimeMillis());
        Map<String, Object> done = onboarding.status(1L);
        assertEquals(true, done.get("done")); assertNull(done.get("current"));
        assertResources(140000); assertEquals(100, balance.getDiamond());
        onboarding.finish(1L); onboarding.start(1L); onboarding.status(1L);
        assertResources(140000); assertEquals(100, balance.getDiamond());
        assertFalse(done.containsKey("waitingForGather")); assertFalse(done.containsKey("waitingForScoutReturn"));
        verify(mail, times(1)).issueOnboardingCompletionGift(1L);
        assertEquals("locked", task("gather").get("status"));
    }

    @Test
    void skippedOrUnenrolledPlayersCanCompleteExplorationWithoutSeeingCorePrompts() {
        onboarding.skip(1L); onboarding.skip(1L); assertResources(90000);
        assertThrows(IllegalArgumentException.class, () -> onboarding.claimTask(1L, "gather"));
        onboarding.onEvent(1L, "GATHER_COMPLETE", "food", 1);
        assertEquals("completed", task("gather").get("status"));
        onboarding.claimTask(1L, "gather"); onboarding.claimTask(1L, "gather");
        assertEquals(95000, balance.getFood()); assertEquals(90000, balance.getGold()); assertEquals("claimed", task("gather").get("status"));
        assertNull(onboarding.status(1L).get("current")); assertEquals(0, balance.getDiamond());
        verifyNoInteractions(mail);
    }

    @Test
    void firstUseHintIsDeferredUntilUnlockedAndNeverCompletesTask() {
        onboarding.start(1L);
        assertEquals(false, onboarding.beginTask(1L, "gather").get("showHint"));
        assertEquals(false, task("gather").get("hintSeen"));
        WildTile tile = new WildTile(); tile.setType("grainfield"); tile.setTotalRes(800); tile.setMined(0); tiles.add(tile);
        assertEquals("available", task("gather").get("status"));
        assertEquals(true, onboarding.beginTask(1L, "gather").get("showHint"));
        assertEquals(false, onboarding.beginTask(1L, "gather").get("showHint"));
        assertEquals("available", task("gather").get("status"));
        assertEquals("base", current());
        assertThrows(IllegalArgumentException.class, () -> onboarding.beginTask(1L, "unknown"));
    }

    @Test
    void tasksUseIndependentConditionsAndCompletedEventsTakePrecedenceOverLocks() {
        onboarding.start(1L);
        onboarding.onEvent(1L, "ARMY_RECRUIT", "truck", 1);
        assertEquals("completed", task("truck").get("status"));
        assertEquals("locked", task("gather").get("status"));
        onboarding.onEvent(1L, "OFFICER_RECRUIT", null, 1);
        assertEquals("completed", task("officer").get("status"));
        onboarding.onEvent(1L, "TECH_LEVEL", "food_yield", 1);
        assertEquals("completed", task("tech").get("status"));
        assertEquals("base", current());
    }

    @Test
    void zeroHarvestAndSubcityConstructionDoNotCompleteCoreButOptionalTasksAllowSubcities() {
        onboarding.start(1L);
        onboarding.onEvent(1L, "GATHER_COMPLETE", "food", 0);
        assertEquals("locked", task("gather").get("status"));
        when(scope.slot(1L)).thenReturn(1);
        onboarding.onEvent(1L, "BUILD_UPGRADE_DONE", "farm", 1);
        onboarding.onEvent(1L, "ARMY_RECRUIT", "infantry", 1);
        assertEquals(0L, onboarding.status(1L).get("completed"));
        report("scout", Map.of("targetKind", "wild", "showCityInfo", true, "citySlot", 1), false);
        report("battle", Map.of("targetType", "wild", "win", true, "citySlot", 1), true);
        assertEquals(0L, onboarding.status(1L).get("completed"));
        onboarding.onEvent(1L, "GATHER_COMPLETE", "food", 1);
        assertEquals("completed", task("gather").get("status"));
    }

    @Test
    void onlyWinningAttackerReportCanCountAsBattleAndRead() {
        onboarding.start(1L);
        report("battle", Map.of("targetType", "wild", "win", false), true);
        report("battle", Map.of("targetType", "wild", "win", true, "perspective", "defender"), true);
        assertEquals(0L, onboarding.status(1L).get("completed"));
        report("battle", Map.of("targetType", "wild", "win", true), true);
        assertEquals(2L, onboarding.status(1L).get("completed"));
        assertEquals(false, onboarding.status(1L).get("done"));
    }

    @Test
    void recoveryIsOneTimeAndDoesNotCountAsTraining() {
        onboarding.start(1L); assertThrows(IllegalArgumentException.class, () -> onboarding.recover(1L));
        report("battle", Map.of("targetType", "wild", "win", false), false);
        onboarding.recover(1L); onboarding.recover(1L);
        assertEquals(30, troops.stream().filter(a -> "infantry".equals(a.getType())).findFirst().orElseThrow().getCount());
        assertEquals(30, troops.stream().filter(a -> "infantry".equals(a.getType())).findFirst().orElseThrow().getCount());
        assertEquals(2, troops.stream().filter(a -> "truck".equals(a.getType())).findFirst().orElseThrow().getCount());
        assertTrue(troops.stream().noneMatch(a -> "scout".equals(a.getType())));
        assertEquals(0L, onboarding.status(1L).get("completed"));
    }

    private void assertResources(long expected) {
        assertAll(() -> assertEquals(expected, balance.getFood()),
                () -> assertEquals(expected, balance.getSteel()),
                () -> assertEquals(expected, balance.getOil()),
                () -> assertEquals(expected, balance.getRare()),
                () -> assertEquals(expected, balance.getGold()));
    }

    private ScoutReport report(String type, Map<String, Object> data, boolean read) {
        ScoutReport report = new ScoutReport(); report.setType(type); report.setData(JsonUtil.toJson(data));
        report.setPersistedRealAt(System.currentTimeMillis());
        report.setCreatedAt(System.currentTimeMillis()); report.setReadAt(read ? System.currentTimeMillis() : 0L);
        intel.add(report); return report;
    }
    private String current() { return (String) ((Map<?, ?>) onboarding.status(1L).get("current")).get("id"); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> task(String id) {
        return ((List<Map<String, Object>>) onboarding.status(1L).get("optionalTasks")).stream().filter(t -> id.equals(t.get("id"))).findFirst().orElseThrow();
    }
}
