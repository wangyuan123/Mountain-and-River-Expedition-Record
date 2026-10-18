package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.PlayerGuideRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.CityScope;
import com.wargame.service.quest.OnboardingService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OnboardingTest extends BaseServiceTest {
    @Autowired OnboardingService onboarding;
    @Autowired ArmyService army;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired PlayerGuideRepository guides;
    @Autowired CityScope scope;
    Long playerId;

    @BeforeEach
    void setup() {
        createTestWorld(); playerId = createTestPlayer("onboarding", 30).getId();
        gameStateService.initializeNewPlayer(playerId);
    }

    @Test
    void fiveCoreActionsHaveNoGatheringOrFacilityChain() {
        assertEquals(List.of("base", "infantry", "scout", "occupy", "report"), OnboardingService.OBJECTIVES.stream().map(OnboardingService.Objective::id).toList());
        assertEquals("base", current());
        assertEquals(1, buildingRepository.findByPlayerIdAndType(playerId, "factory").get(0).getLevel());
        assertEquals(1, armyUnitRepository.findByPlayerIdAndType(playerId, "scout").get(0).getCount());
        assertEquals(1, armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0).getCount());
        assertEquals(1, technologyRepository.findByPlayerIdAndType(playerId, "recon_level").get(0).getLevel());
        assertFalse(complete("base")); assertFalse(complete("infantry"));
        assertEquals("locked", task("gather").get("status"));
        assertEquals("locked", task("tech").get("status"), "初始赠送侦察技术不能冒充研究完成");
        onboarding.start(playerId);
        assertEquals(1, armyUnitRepository.findByPlayerIdAndType(playerId, "scout").get(0).getCount());
    }

    @Test
    void realStarterEconomyCompletesFiveActionsWithoutAirportOfficerOrGathering() {
        onboarding.pause(playerId, true);
        assertThrows(IllegalArgumentException.class, () -> onboarding.claim(playerId, "base"));
        build("command");
        assertTrue(complete("base")); assertEquals("infantry", current());
        int food = getResources(playerId).getFood(); onboarding.claim(playerId, "base");
        assertEquals(food, getResources(playerId).getFood(), "建设补给自动到账后不重复发放");
        recruit("infantry", 1); assertEquals("scout", current());
        Map<String, Object> target = onboarding.target(playerId, false);
        Long targetId = ((Number) target.get("id")).longValue();
        assertEquals(Map.of(), target.get("garrison"));
        assertEquals(targetId, onboarding.target(playerId, false).get("id"));
        March scout = dispatch(targetId, "wild", "scout", Map.of("scout", 1));
        assertEquals(true, onboarding.status(playerId).get("scouting"));
        assertFalse(complete("scout")); arrive(scout);
        assertTrue(scout.getReturning());
        assertTrue(complete("scout"), "情报生成后即可推进，无需等侦察部队返城");
        assertEquals("occupy", current());
        assertEquals(true, onboarding.target(playerId, false).get("scouted"));
        March conquer = dispatch(targetId, "wild", "conquer", Map.of("infantry", 50));
        arrive(conquer);
        assertTrue(complete("occupy")); assertEquals("report", current());
        ScoutReport battle = scoutReportRepository.findByPlayerId(playerId).stream().filter(r -> "battle".equals(r.getType())).findFirst().orElseThrow();
        assertTrue(JsonUtil.parseTree(battle.getData()).path("win").asBoolean());
        int diamond = getResources(playerId).getDiamond();
        battle.setReadAt(System.currentTimeMillis()); scoutReportRepository.save(battle);
        var result = onboarding.status(playerId);
        assertEquals(true, result.get("done")); assertNull(result.get("current"));
        assertEquals(diamond + 100, getResources(playerId).getDiamond());
        int afterFood = getResources(playerId).getFood();
        onboarding.finish(playerId); onboarding.status(playerId); onboarding.start(playerId);
        assertEquals(afterFood, getResources(playerId).getFood());
        assertEquals(diamond + 100, getResources(playerId).getDiamond());
        assertEquals("available", task("gather").get("status"));
        assertFalse(result.containsKey("waitingForGather"));
    }

    @Test
    void directGatherAfterSkippingCompletesOptionalTaskAndRewardIsIdempotent() {
        Map<String, Object> target = onboarding.target(playerId, false);
        Long targetId = ((Number) target.get("id")).longValue();
        WildTile tile = wildTileRepository.findById(targetId).orElseThrow();
        tile.setOccupied(true); tile.setOccupiedBy(playerId); wildTileRepository.save(tile);
        onboarding.skip(playerId);
        assertEquals(targetId, onboarding.target(playerId, true).get("id"), "跳过后仍可推荐采集目标");
        assertEquals(true, onboarding.beginTask(playerId, "gather").get("showHint"));
        assertEquals(false, onboarding.beginTask(playerId, "gather").get("showHint"));
        assertEquals("available", task("gather").get("status"), "点击任务不算采集完成");
        March gather = dispatch(targetId, "wild_gather", "gather", Map.of("truck", 1));
        assertEquals("in_progress", task("gather").get("status"));
        arrive(gather); assertEquals("in_progress", task("gather").get("status"));
        assertTrue(gather.getGatherAmount() > 0, "基础卡车应具备真实采集负重");
        gather.setGatherEndAt(System.currentTimeMillis() - 1); marchRepository.save(gather);
        marchService.processMarches(playerId, System.currentTimeMillis());
        assertEquals("in_progress", task("gather").get("status"));
        arrive(gather);
        assertEquals("completed", task("gather").get("status"));
        int before = getResources(playerId).getFood();
        onboarding.claimTask(playerId, "gather"); onboarding.claimTask(playerId, "gather");
        assertEquals(before + 5000, getResources(playerId).getFood());
        assertEquals("claimed", task("gather").get("status"));
        assertNull(onboarding.status(playerId).get("current"));
    }

    @Test
    void unlockedHintsAreDeferredAndTasksDoNotDependOnEachOther() {
        assertEquals(false, onboarding.beginTask(playerId, "gather").get("showHint"));
        assertEquals(false, task("gather").get("hintSeen"));
        assertEquals("available", task("truck").get("status"));
        onboarding.onEvent(playerId, "ARMY_RECRUIT", "truck", 1);
        assertEquals("completed", task("truck").get("status"));
        assertEquals("locked", task("gather").get("status"));
        assertThrows(IllegalArgumentException.class, () -> onboarding.claimTask(playerId, "gather"));
        assertThrows(IllegalArgumentException.class, () -> onboarding.beginTask(playerId, "unknown"));
    }

    @Test
    void zeroHarvestAndSubcityEventsCannotCompleteCoreActions() {
        onboarding.onEvent(playerId, "GATHER_COMPLETE", "food", 0);
        try (var ignored = scope.enter(playerId, 1)) {
            onboarding.onEvent(playerId, "BUILD_UPGRADE_DONE", "farm", 1);
            onboarding.onEvent(playerId, "ARMY_RECRUIT", "infantry", 1);
            onboarding.onEvent(playerId, "GATHER_COMPLETE", "food", 1);
        }
        assertFalse(complete("base")); assertFalse(complete("infantry"));
        assertEquals("completed", task("gather").get("status"), "可选探索允许分城的真实完成事件");
        report("scout", Map.of("targetKind", "wild", "showCityInfo", true, "citySlot", 1), false);
        report("battle", Map.of("targetType", "wild", "win", true, "citySlot", 1), true);
        assertFalse(complete("scout")); assertFalse(complete("occupy")); assertFalse(complete("report"));
    }

    @Test
    void battleWinWithoutOccupationCountsButLossAndDefenderReportsDoNot() {
        report("battle", Map.of("targetType", "wild", "win", false), true);
        report("battle", Map.of("targetType", "wild", "win", true, "perspective", "defender"), true);
        assertFalse(complete("occupy")); assertFalse(complete("report"));
        report("battle", Map.of("targetType", "wild", "win", true, "wildConquered", false), true);
        assertTrue(complete("occupy")); assertTrue(complete("report"));
        assertFalse((Boolean) onboarding.status(playerId).get("done"), "其他三个目标未完成不能毕业");
    }

    @Test
    void skippingOnlyPaysUnclaimedStageSuppliesAndLeavesExplorationOpen() {
        onboarding.onEvent(playerId, "BUILD_UPGRADE_DONE", "farm", 1);
        int food = getResources(playerId).getFood(), diamond = getResources(playerId).getDiamond();
        onboarding.skip(playerId); onboarding.skip(playerId); onboarding.claim(playerId, "base");
        assertEquals(food + 8000, getResources(playerId).getFood()); assertEquals(diamond, getResources(playerId).getDiamond());
        assertEquals(true, onboarding.status(playerId).get("done")); assertNull(onboarding.status(playerId).get("current"));
        assertThrows(IllegalArgumentException.class, () -> onboarding.target(playerId, false));
        assertThrows(IllegalArgumentException.class, () -> onboarding.recover(playerId));
        onboarding.onEvent(playerId, "OFFICER_RECRUIT", null, 1);
        assertEquals("completed", task("officer").get("status"));
    }

    @Test
    void failedBattleOffersOneRecoveryAndTakenTargetCanBeReplaced() {
        assertThrows(IllegalArgumentException.class, () -> onboarding.recover(playerId));
        Map<String, Object> first = onboarding.target(playerId, false);
        WildTile tile = wildTileRepository.findById(((Number) first.get("id")).longValue()).orElseThrow();
        tile.setOccupied(true); tile.setOccupiedBy(999L); wildTileRepository.save(tile);
        assertNotEquals(first.get("id"), onboarding.target(playerId, false).get("id"));
        report("battle", Map.of("targetType", "wild", "win", false), false);
        onboarding.recover(playerId); onboarding.recover(playerId);
        assertEquals(80, armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount());
        assertFalse(complete("infantry"));
    }

    @Test
    void previousFifteenStepDataDoesNotAffectNewProgress() {
        Long other = createTestPlayer("old-player", 30).getId();
        PlayerGuide old = new PlayerGuide(); old.setPlayerId(other); old.setStepId("ob2_plan"); old.setStatus("economy"); guides.save(old);
        assertEquals(false, onboarding.status(other).get("enrolled"));
        onboarding.start(other); assertFalse((Boolean) onboarding.status(other).get("done"));
        onboarding.pause(other, true); assertEquals(true, onboarding.status(other).get("paused"));
        onboarding.pause(other, false); assertEquals(false, onboarding.status(other).get("paused"));
    }

    private void report(String type, Map<String, Object> data, boolean read) {
        ScoutReport r = new ScoutReport(); r.setPlayerId(playerId); r.setType(type); r.setData(JsonUtil.toJson(data));
        r.setCreatedAt(System.currentTimeMillis()); r.setReadAt(read ? System.currentTimeMillis() : 0L); scoutReportRepository.save(r);
    }
    private String current() { return (String) ((Map<?, ?>) onboarding.status(playerId).get("current")).get("id"); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> task(String id) { return ((List<Map<String, Object>>) onboarding.status(playerId).get("optionalTasks")).stream().filter(t -> id.equals(t.get("id"))).findFirst().orElseThrow(); }
    @SuppressWarnings("unchecked")
    private boolean complete(String id) { return ((List<Map<String, Object>>) onboarding.status(playerId).get("objectives")).stream().anyMatch(o -> id.equals(o.get("id")) && Boolean.TRUE.equals(o.get("complete"))); }
    private void build(String type) {
        assertTrue((Boolean) buildService.upgrade(playerId, type, 0).get("success"));
        constructionRepository.findByPlayerId(playerId).forEach(q -> { q.setFinishAt(System.currentTimeMillis() - 1); constructionRepository.save(q); });
        buildService.completeUpgrade(playerId, System.currentTimeMillis());
    }
    private void recruit(String type, int count) {
        assertTrue((Boolean) army.recruit(playerId, type, count).get("success"));
        queues.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).forEach(q -> { q.setFinishesAt(System.currentTimeMillis() - 1); queues.save(q); });
        army.completeProduction(playerId, System.currentTimeMillis());
    }
    private March dispatch(Long id, String kind, String action, Map<String, Integer> units) {
        return dispatch(id, kind, action, units, null);
    }
    private March dispatch(Long id, String kind, String action, Map<String, Integer> units, Long officerId) {
        return marchService.createDispatch(playerId, new DispatchRequest(kind, id, action, units, officerId, Map.of()));
    }
    private void arrive(March march) {
        march.setArriveAt(System.currentTimeMillis() - 1); marchRepository.save(march);
        marchService.processMarches(playerId, System.currentTimeMillis());
    }
}
