package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.PlayerGuideRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.CityScope;
import com.wargame.service.OfficerService;
import com.wargame.service.TechService;
import com.wargame.service.quest.OnboardingService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OnboardingTest extends BaseServiceTest {
    @Autowired OnboardingService onboarding;
    @Autowired ArmyService army;
    @Autowired OfficerService officers;
    @Autowired TechService tech;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired com.wargame.repository.TechResearchQueueRepository techQueues;
    @Autowired PlayerGuideRepository guides;
    @Autowired CityScope scope;
    Long playerId;

    @BeforeEach
    void setup() {
        createTestWorld();
        playerId = createTestPlayer("onboarding", 30).getId();
        gameStateService.initializeNewPlayer(playerId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realStarterEconomyCompletesTheWholeExpeditionEvenWhenPromptsAreSkipped(boolean legacyScoutReport) {
        assertTrue((Boolean) onboarding.status(playerId).get("enrolled"), "新账号完成城市初始化后应自动加入新手行动");
        assertFalse(complete("train"), "赠送的50步兵不能算生产完成");
        assertThrows(IllegalArgumentException.class, () -> onboarding.claim(playerId, "base"));
        assertEquals(1200, ((Map<?, ?>) gameStateService.getGameState(playerId).get("population")).get("capacity"));
        onboarding.pause(playerId, true);
        build("command"); build("farm"); build("factory");
        assertTrue(complete("base"));
        int steel = getResources(playerId).getSteel();
        onboarding.claim(playerId, "base");
        onboarding.claim(playerId, "base");
        assertEquals(steel + 4000, getResources(playerId).getSteel(), "重复请求只能入账一次");
        recruit("infantry", 3); recruit("truck", 2); recruit("scout", 1);
        assertTrue(complete("train"));
        build("depot"); build("lab"); build("radar"); research("recon_level");
        assertTrue(complete("recon"));
        assertEquals("officer", ((Map<?, ?>) onboarding.status(playerId).get("current")).get("id"));
        assertFalse(complete("officer"), "建造军校前没有军官，不能提前进入侦察步骤");
        build("academy");
        assertFalse(complete("officer"), "只建军校还不算完成招募");
        assertTrue((Boolean) officers.refreshAcademy(playerId).get("success"));
        int officerGold = getResources(playerId).getGold();
        Map<String, Object> recruited = officers.recruit(playerId, 0);
        assertTrue((Boolean) recruited.get("success"));
        Long officerId = ((Number) recruited.get("officerId")).longValue();
        assertTrue(complete("officer"));
        assertEquals("scout", ((Map<?, ?>) onboarding.status(playerId).get("current")).get("id"));
        int paidGold = getResources(playerId).getGold();
        assertEquals(officerGold - officerRepository.findById(officerId).orElseThrow().getStar() * 80 + 200, paidGold,
                "招募成功后自动发放本步奖励");
        onboarding.onEvent(playerId, "OFFICER_RECRUIT", null, 1);
        assertEquals(paidGold, getResources(playerId).getGold(), "重复招募事件不能重复领取奖励");

        Map<String, Object> target = onboarding.target(playerId, false);
        Long targetId = ((Number) target.get("id")).longValue();
        assertFalse((Boolean) target.get("scouted"));
        assertEquals(Map.of(), target.get("garrison"), "推荐不能泄露未侦察的守军");
        assertEquals(targetId, onboarding.target(playerId, false).get("id"), "重复定位不能刷出新资源点");
        March scout = dispatch(targetId, "wild", "scout", Map.of("scout", 1), officerId);
        assertEquals(officerId, scout.getCommanderId());
        assertEquals(true, onboarding.status(playerId).get("waitingForScoutReturn"));
        assertFalse(complete("scout"), "出征途中不能完成侦察目标");
        int scoutRewardBefore = getResources(playerId).getDiamond();
        arrive(scout);
        ScoutReport scoutReport = scoutReportRepository.findByPlayerId(playerId).stream()
                .filter(r -> "scout".equals(r.getType())).findFirst().orElseThrow();
        var scoutData = JsonUtil.parseObjMap(scoutReport.getData());
        assertEquals(scout.getId().longValue(), ((Number) scoutData.get("marchId")).longValue());
        if (legacyScoutReport) {
            scoutData.remove("marchId");
            scoutReport.setData(JsonUtil.toJson(scoutData));
            scoutReportRepository.save(scoutReport);
        }
        assertTrue(scout.getReturning());
        assertTrue((Boolean) onboarding.target(playerId, false).get("scouted"), "情报仍在到达野地时送达");
        assertEquals(true, onboarding.status(playerId).get("waitingForScoutReturn"));
        assertFalse(complete("scout"), "已经生成情报但未返城时，不能提前推进指引");
        assertEquals("scout", ((Map<?, ?>) onboarding.status(playerId).get("current")).get("id"));
        assertEquals(scoutRewardBefore, getResources(playerId).getDiamond(), "返城前不发放侦察目标奖励");
        assertThrows(IllegalArgumentException.class, () -> onboarding.claim(playerId, "scout"));
        scout.setArriveAt(System.currentTimeMillis() - 1);
        marchRepository.save(scout);
        assertFalse(complete("scout"), "不能只凭倒计时结束判断返城，须实际结算部队入库");
        arrive(scout);
        assertTrue(complete("scout"));
        assertEquals(false, onboarding.status(playerId).get("waitingForScoutReturn"));
        assertEquals("occupy", ((Map<?, ?>) onboarding.status(playerId).get("current")).get("id"));
        assertEquals(1, armyUnitRepository.findByPlayerIdAndType(playerId, "scout").get(0).getCount());
        assertEquals(scoutRewardBefore + 15, getResources(playerId).getDiamond(), "多次读取只能结算一次侦察奖励");
        onboarding.claim(playerId, "scout");
        assertTrue((Boolean) onboarding.target(playerId, false).get("scouted"));

        March conquer = dispatch(targetId, "wild", "conquer", Map.of("infantry", 50), officerId);
        assertEquals(officerId, conquer.getCommanderId());
        arrive(conquer);
        assertTrue(complete("occupy"));
        assertFalse(complete("report"));
        ScoutReport battle = scoutReportRepository.findByPlayerId(playerId).stream().filter(r -> "battle".equals(r.getType())).findFirst().orElseThrow();
        assertTrue(JsonUtil.parseTree(battle.getData()).path("win").asBoolean());
        battle.setReadAt(System.currentTimeMillis()); scoutReportRepository.save(battle);
        assertTrue(complete("report"));
        arrive(conquer);

        assertEquals(targetId, onboarding.target(playerId, true).get("id"));
        March gather = dispatch(targetId, "wild_gather", "gather", Map.of("truck", 2), officerId);
        assertEquals(officerId, gather.getCommanderId());
        arrive(gather);
        assertFalse(complete("gather"), "到达资源地还不算补给入库");
        gather.setGatherEndAt(System.currentTimeMillis() - 1); marchRepository.save(gather);
        marchService.processMarches(playerId, System.currentTimeMillis());
        assertFalse(complete("gather"), "采集完成但仍在返程时不能领取发展补给");
        int food = getResources(playerId).getFood();
        arrive(gather);
        assertTrue(complete("gather"));
        assertTrue(getResources(playerId).getFood() > food);
        onboarding.claim(playerId, "gather");
        assertThrows(IllegalArgumentException.class, () -> onboarding.choosePlan(playerId, "economy"));
        build("refinery");
        assertTrue((Boolean) onboarding.choosePlan(playerId, "economy").get("done"));
        assertTrue((Boolean) onboarding.status(playerId).get("paused"));
        assertTrue((Boolean) onboarding.start(playerId).get("done"), "重复开启不重置领奖和完成记录");
    }

    @Test
    void oldGuideRecordsDoNotAutoEnrollAndPreferencesSurviveReload() {
        Long other = createTestPlayer("old-player", 30).getId();
        PlayerGuide old = new PlayerGuide(); old.setPlayerId(other); old.setStepId("g_done"); old.setStatus("done"); guides.save(old);
        assertEquals(false, onboarding.status(other).get("enrolled"));
        assertEquals(true, onboarding.start(other).get("enrolled"));
        onboarding.pause(other, true);
        assertEquals(true, onboarding.status(other).get("paused"));
        onboarding.pause(other, false);
        assertEquals(false, onboarding.status(other).get("paused"));
        assertEquals(false, onboarding.status(playerId).get("paused"));
    }

    @Test
    void previouslyGraduatedPlayerIsExemptWithoutReceivingNewRecruitmentReward() {
        PlayerGuide graduate = new PlayerGuide();
        graduate.setPlayerId(playerId);
        graduate.setStepId("ob2_plan");
        graduate.setStatus("economy");
        graduate.setCompletedAt(System.currentTimeMillis());
        guides.save(graduate);
        int gold = getResources(playerId).getGold();
        Map<String, Object> status = onboarding.status(playerId);
        assertEquals(true, status.get("done"));
        assertNull(status.get("current"));
        var objectives = (List<Map<String, Object>>) status.get("objectives");
        var officer = objectives.stream().filter(o -> "officer".equals(o.get("id"))).findFirst().orElseThrow();
        assertEquals(true, officer.get("complete"));
        assertEquals(true, officer.get("exempt"));
        assertEquals(false, officer.get("rewarded"));
        onboarding.onEvent(playerId, "OFFICER_RECRUIT", null, 1);
        assertEquals(gold, getResources(playerId).getGold());
        assertEquals(false, ((List<Map<String, Object>>) onboarding.status(playerId).get("objectives")).stream()
                .filter(o -> "officer".equals(o.get("id"))).findFirst().orElseThrow().get("rewarded"));
    }

    @Test
    void graduationRewardIsPaidOnceAfterAllSingleStepRewards() {
        onboarding.start(playerId);
        for (var objective : OnboardingService.OBJECTIVES) {
            if ("plan".equals(objective.id())) continue;
            PlayerGuide progress = new PlayerGuide();
            progress.setPlayerId(playerId);
            progress.setStepId("ob2_" + objective.id());
            progress.setStatus("done");
            progress.setCompletedAt(System.currentTimeMillis());
            guides.save(progress);
            PlayerGuide reward = new PlayerGuide();
            reward.setPlayerId(playerId);
            reward.setStepId("ob2_reward_" + objective.id());
            reward.setStatus("claimed");
            reward.setCompletedAt(System.currentTimeMillis());
            guides.save(reward);
        }
        int beforeFood = getResources(playerId).getFood();
        int beforeSteel = getResources(playerId).getSteel();
        int beforeOil = getResources(playerId).getOil();
        int beforeRare = getResources(playerId).getRare();
        int beforeGold = getResources(playerId).getGold();
        int beforeDiamond = getResources(playerId).getDiamond();
        var first = onboarding.choosePlan(playerId, "explore");
        Resources after = getResources(playerId);
        assertTrue((Boolean) first.get("done"));
        assertEquals(beforeFood + 47000, after.getFood());
        assertEquals(beforeSteel + 39000, after.getSteel());
        assertEquals(beforeOil + 15300, after.getOil());
        assertEquals(beforeRare + 4800, after.getRare());
        assertEquals(beforeGold + 6000, after.getGold());
        assertTrue(((List<Map<String, Object>>) first.get("supplies")).stream().allMatch(s -> Boolean.TRUE.equals(s.get("claimed"))));
        assertEquals(beforeDiamond + 275, after.getDiamond());
        onboarding.choosePlan(playerId, "explore");
        Resources reloaded = getResources(playerId);
        assertEquals(after.getFood(), reloaded.getFood());
        assertEquals(after.getSteel(), reloaded.getSteel());
        assertEquals(after.getDiamond(), reloaded.getDiamond());
    }

    @Test
    void skippingPaysOnlyRemainingSuppliesAndCannotRestartOrClaimAgain() {
        int initialFood = getResources(playerId).getFood();
        int initialSteel = getResources(playerId).getSteel();
        build("command"); build("farm"); build("factory");
        onboarding.claim(playerId, "base");
        int beforeSkipFood = getResources(playerId).getFood();
        int beforeSkipSteel = getResources(playerId).getSteel();
        int beforeSkipOil = getResources(playerId).getOil();
        int beforeSkipRare = getResources(playerId).getRare();
        int beforeSkipGold = getResources(playerId).getGold();
        var result = onboarding.skip(playerId);
        assertEquals(true, result.get("done"));
        assertEquals(true, result.get("skipped"));
        assertEquals(true, result.get("paused"));
        assertNull(result.get("current"));
        assertEquals(beforeSkipFood + 8000, getResources(playerId).getFood());
        assertEquals(beforeSkipSteel + 7000, getResources(playerId).getSteel());
        assertEquals(beforeSkipOil + 3600, getResources(playerId).getOil());
        assertEquals(beforeSkipRare + 800, getResources(playerId).getRare());
        assertEquals(beforeSkipGold + 1500, getResources(playerId).getGold());
        assertEquals(3, ((List<?>) result.get("supplies")).size());
        onboarding.skip(playerId);
        onboarding.claim(playerId, "base"); onboarding.claim(playerId, "scout"); onboarding.claim(playerId, "gather");
        onboarding.start(playerId);
        assertEquals(beforeSkipFood + 8000, getResources(playerId).getFood());
        assertEquals(beforeSkipSteel + 7000, getResources(playerId).getSteel());
        assertTrue(getResources(playerId).getFood() > initialFood);
        assertTrue(getResources(playerId).getSteel() > initialSteel);
        assertThrows(IllegalArgumentException.class, () -> onboarding.choosePlan(playerId, "economy"));
        assertThrows(IllegalArgumentException.class, () -> onboarding.recover(playerId));
        assertThrows(IllegalArgumentException.class, () -> onboarding.target(playerId, false));
    }

    @Test
    void previouslyUnenrolledAccountCanStartAndSkipWithoutReplayingOldRewards() {
        Long oldPlayer = createTestPlayer("returning-player", 30).getId();
        assertEquals(false, onboarding.status(oldPlayer).get("enrolled"));
        int food = getResources(oldPlayer).getFood();
        assertEquals(true, onboarding.skip(oldPlayer).get("enrolled"));
        assertEquals(food + 12000, getResources(oldPlayer).getFood());
        onboarding.skip(oldPlayer);
        assertEquals(food + 12000, getResources(oldPlayer).getFood());
    }

    @Test
    void zeroHarvestAndOtherCitiesDoNotGrantProgressOrRewards() {
        Map<String, Object> target = onboarding.target(playerId, false);
        WildTile tile = wildTileRepository.findById(((Number) target.get("id")).longValue()).orElseThrow();
        tile.setOccupied(true); tile.setOccupiedBy(playerId); tile.setGarrison(JsonUtil.toJson(Map.of("truck", 1)));
        tile.setGathering(true); tile.setGatherStartAt(System.currentTimeMillis() + 1000); tile.setGatherEndAt(System.currentTimeMillis() + 60000);
        tile.setGatherLoad(0); tile.setGatherRes("food"); wildTileRepository.save(tile);
        marchService.harvestWild(playerId, tile.getId());
        assertFalse(complete("gather"));
        try (var ignored = scope.enter(playerId, 1)) {
            onboarding.onEvent(playerId, "GATHER_COMPLETE", "food", 1);
            onboarding.onEvent(playerId, "ARMY_RECRUIT", "infantry", 10);
        }
        assertFalse(complete("gather")); assertFalse(complete("train"));
    }

    @Test
    void failedBattleOffersOneRecoveryAndTakenTargetCanBeReplaced() {
        assertThrows(IllegalArgumentException.class, () -> onboarding.recover(playerId));
        Map<String, Object> first = onboarding.target(playerId, false);
        WildTile tile = wildTileRepository.findById(((Number) first.get("id")).longValue()).orElseThrow();
        tile.setOccupied(true); tile.setOccupiedBy(999L); wildTileRepository.save(tile);
        assertNotEquals(first.get("id"), onboarding.target(playerId, false).get("id"));
        ScoutReport loss = new ScoutReport(); loss.setPlayerId(playerId); loss.setType("battle"); loss.setCreatedAt(System.currentTimeMillis());
        loss.setData(JsonUtil.toJson(Map.of("targetType", "wild", "win", false))); scoutReportRepository.save(loss);
        onboarding.recover(playerId); onboarding.recover(playerId);
        assertEquals(80, armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount());
        assertFalse(complete("train"), "补员不等同于生产训练");
    }

    private boolean complete(String id) {
        var objectives = (List<Map<String, Object>>) onboarding.status(playerId).get("objectives");
        return objectives.stream().anyMatch(o -> id.equals(o.get("id")) && Boolean.TRUE.equals(o.get("complete")));
    }
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
    private void research(String type) {
        assertTrue((Boolean) tech.upgrade(playerId, type).get("success"));
        techQueues.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).forEach(q -> {
            q.setFinishesAt(System.currentTimeMillis() - 1);
            techQueues.save(q);
        });
        tech.settleCompletedResearch(playerId, System.currentTimeMillis());
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
