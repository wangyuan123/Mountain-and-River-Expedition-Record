package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.PlayerGuideRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.CityScope;
import com.wargame.service.TechService;
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
    @Autowired TechService tech;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired PlayerGuideRepository guides;
    @Autowired CityScope scope;
    Long playerId;

    @BeforeEach
    void setup() {
        createTestWorld();
        playerId = createTestPlayer("onboarding", 30).getId();
        gameStateService.initializeNewPlayer(playerId);
    }

    @Test
    void realStarterEconomyCompletesTheWholeExpeditionEvenWhenPromptsAreSkipped() {
        assertFalse(complete("train"), "赠送的50步兵不能算生产完成");
        assertThrows(IllegalArgumentException.class, () -> onboarding.claim(playerId, "base"));
        assertEquals(1200, ((Map<?, ?>) gameStateService.getGameState(playerId).get("population")).get("capacity"));
        onboarding.pause(playerId, true);
        build("command"); build("farm"); build("factory");
        assertTrue(complete("base"));
        int steel = getResources(playerId).getSteel();
        onboarding.claim(playerId, "base");
        onboarding.claim(playerId, "base");
        assertEquals(steel + 1000, getResources(playerId).getSteel(), "重复请求只能入账一次");
        recruit("infantry", 3); recruit("truck", 2); recruit("scout", 1);
        assertTrue(complete("train"));
        build("lab"); assertTrue((Boolean) tech.upgrade(playerId, "recon_level").get("success"));
        assertTrue(complete("recon"));

        Map<String, Object> target = onboarding.target(playerId, false);
        Long targetId = ((Number) target.get("id")).longValue();
        assertFalse((Boolean) target.get("scouted"));
        assertEquals(Map.of(), target.get("garrison"), "推荐不能泄露未侦察的守军");
        assertEquals(targetId, onboarding.target(playerId, false).get("id"), "重复定位不能刷出新资源点");
        March scout = dispatch(targetId, "wild", "scout", Map.of("scout", 1));
        arrive(scout); arrive(scout);
        assertTrue(complete("scout"));
        onboarding.claim(playerId, "scout");
        assertTrue((Boolean) onboarding.target(playerId, false).get("scouted"));

        March conquer = dispatch(targetId, "wild", "conquer", Map.of("infantry", 50));
        arrive(conquer);
        assertTrue(complete("occupy"));
        assertFalse(complete("report"));
        ScoutReport battle = scoutReportRepository.findByPlayerId(playerId).stream().filter(r -> "battle".equals(r.getType())).findFirst().orElseThrow();
        assertTrue(JsonUtil.parseTree(battle.getData()).path("win").asBoolean());
        battle.setReadAt(System.currentTimeMillis()); scoutReportRepository.save(battle);
        assertTrue(complete("report"));
        arrive(conquer);

        assertEquals(targetId, onboarding.target(playerId, true).get("id"));
        March gather = dispatch(targetId, "wild_gather", "gather", Map.of("truck", 2));
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
    private March dispatch(Long id, String kind, String action, Map<String, Integer> units) {
        return marchService.createDispatch(playerId, new DispatchRequest(kind, id, action, units, null, Map.of()));
    }
    private void arrive(March march) {
        march.setArriveAt(System.currentTimeMillis() - 1); marchRepository.save(march);
        marchService.processMarches(playerId, System.currentTimeMillis());
    }
}
