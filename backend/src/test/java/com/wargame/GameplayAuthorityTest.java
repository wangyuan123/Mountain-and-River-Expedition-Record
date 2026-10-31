package com.wargame;

import com.wargame.model.constants.*;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.entity.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 预览与正式执行共用规则，且预览不能扣费、跨城读取或授予资产。 */
class GameplayAuthorityTest extends BaseServiceTest {
    @Autowired GameplayViewService views;
    @Autowired ArmyService armies;
    @Autowired TechService techs;
    @Autowired CityScope scope;
    @Autowired VipService vip;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @SuppressWarnings("unchecked")
    @Test void completeStateContainsAuthoritativeCatalogAndEveryPreview() throws Exception {
        var player = createTestPlayer();
        createBuilding(player.getId(), "command", 10);
        createBuilding(player.getId(), "lab", 10);
        createBuilding(player.getId(), "factory", 10);
        var state = gameStateService.getGameState(player.getId());
        var gameplay = (Map<String, Object>) state.get("gameplay");
        var catalog = (Map<String, Object>) gameplay.get("catalog");
        assertEquals(UnitDef.UNITS.size(), ((Map<?, ?>) catalog.get("units")).size());
        assertFalse(((Map<?, ?>) catalog.get("equipmentItems")).isEmpty());
        assertEquals(3, gameplay.get("maxConcurrent"));
        var constructions = (Map<String, Object>) gameplay.get("buildings");
        var farm = (Map<String, Object>) constructions.get("farm");
        assertEquals(10, farm.get("maxLevel"));
        var levels = (List<Map<String, Object>>) farm.get("levels");
        assertEquals(30, levels.get(0).get("duration"));
        assertEquals(24, ((Map<String, Object>) levels.get(1).get("refund")).get("steel"));
        assertNotNull(JsonUtil.toJson(state));
        // 可选择导出真实服务器快照，供前端契约测试使用；普通测试不写仓库文件。
        String fixture = System.getProperty("gameplay.fixture");
        if (fixture != null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixture), JsonUtil.toJson(state));
    }

    @SuppressWarnings("unchecked")
    @Test void vipBuildQuoteAndRefundMatchExecutionRules() {
        var player = createTestPlayer(); player.setVipLevel(2); playerRepository.save(player);
        createBuilding(player.getId(), "command", 10);
        var tech = new Technology(); tech.setPlayerId(player.getId()); tech.setType("log_build"); tech.setLevel(10); technologyRepository.save(tech);
        var previews = buildService.previewLevels(player.getId());
        var levels = (List<Map<String, Object>>) ((Map<?, ?>) previews.get("farm")).get("levels");
        assertEquals(24, ((Map<?, ?>) levels.get(1).get("refund")).get("steel"));
        long before = resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getSteel();
        assertEquals(true, buildService.upgrade(player.getId(), "farm", 0).get("success"));
        var job = constructionRepository.findByPlayerIdAndCitySlot(player.getId(), 0).get(0);
        assertEquals(((Number) levels.get(0).get("duration")).longValue(), (job.getFinishAt() - job.getStartAt()) / 1000);
        assertEquals(((Number) ((Map<?, ?>) levels.get(0).get("cost")).get("steel")).longValue(), before - resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getSteel());
    }

    @Test void trainingPreviewUsesRawChannelsAndCannotSpendResources() {
        var player = createTestPlayer();
        for (int i = 0; i < 10; i++) createBuilding(player.getId(), "factory", 10);
        long before = resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getSteel();
        assertEquals(1.5, armies.trainingStats(player.getId(), "factory").avgSpeed());
        var quote = views.preview(player.getId(), new GameplayViewService.Preview("recruit", "infantry", null, 2, null, null));
        assertNotNull(quote.get("rulesVersion"));
        assertEquals(before, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getSteel());
        assertThrows(IllegalArgumentException.class, () -> views.preview(player.getId(), new GameplayViewService.Preview("recruit", "infantry", null, -1, null, null)));
        assertThrows(IllegalArgumentException.class, () -> views.preview(player.getId(), new GameplayViewService.Preview("recruit", "infantry", null, Integer.MAX_VALUE, null, null)));
    }

    @Test void officerPreviewsCannotReadAnotherPlayersOfficer() {
        var first = createTestPlayer("first", 30); var second = createTestPlayer("second", 30);
        Officer officer = new Officer(); officer.setPlayerId(second.getId()); officer.setName("军官"); officer.setRole("idle"); officer.setStar(1); officer.setLevel(1); officer.setExp(0L); officerRepository.save(officer);
        assertThrows(IllegalArgumentException.class, () -> views.preview(first.getId(), new GameplayViewService.Preview("experience", "expBook", officer.getId(), 1, null, null)));
        try (var ignored = scope.enter(second.getId(), 1)) {
            assertThrows(IllegalArgumentException.class, () -> views.preview(second.getId(), new GameplayViewService.Preview("experience", "expBook", officer.getId(), 1, null, null)));
        }
    }

    @Test void tacticalPreviewUsesFrozenTechAndSkillCycle() {
        var ctx = new BattleService.TechCtx(Map.of("weapon_range", 10, "engine_combustion", 10), Map.of("blitz", 5), 0, 0, new CommandBonus(null, "司令官", 0, 0, 0, 100));
        var inactive = battleService.tacticalStats("infantry", ctx, 3, 2);
        var active = battleService.tacticalStats("infantry", ctx, 5, 2);
        assertEquals(240, active.get("range"));
        assertEquals(false, inactive.get("skillWindow")); assertEquals(true, active.get("skillWindow"));
        assertTrue(((Number) active.get("speed")).doubleValue() > ((Number) inactive.get("speed")).doubleValue());
        assertEquals(110, MarchService.gatheredAt(330, 1000, 4000, 2000));
        assertEquals(330, MarchService.gatheredAt(330, 1000, 4000, 9999));
        assertEquals(0, MarchService.gatheredAt(330, 1000, 4000, 999));
    }

    @SuppressWarnings("unchecked")
    @Test void vipClaimabilityIsComputedFromStoredRecordsAndCannotGrantRewards() {
        var player = createTestPlayer();
        player.setVipLevel(2); player.setTotalRechargeCents(3000L); playerRepository.save(player);
        var status = vip.getVipStatus(player.getId());
        var packs = (List<Map<String, Object>>) status.get("levelPacks");
        assertEquals(2, packs.stream().filter(p -> Boolean.TRUE.equals(p.get("claimable"))).count());
        assertEquals(3000L, status.get("currentLevelRequiredCents"));
        assertEquals(9800L, status.get("nextLevelRequiredCents"));
        assertEquals(false, status.get("isMax"));
        assertEquals(true, vip.claimLevelPack(player.getId(), 1).get("success"));
        var refreshed = (List<Map<String, Object>>) vip.getVipStatus(player.getId()).get("levelPacks");
        assertEquals(false, refreshed.get(0).get("claimable"));
        assertEquals(true, refreshed.get(1).get("claimable"));
    }

    @Test void jsonRejectsFractionalOrOverflowingCountsAndIgnoresForgedQuoteFields() throws Exception {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> json.readValue(
                "{\"action\":\"recruit\",\"id\":\"infantry\",\"count\":1.5}", GameplayViewService.Preview.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> json.readValue(
                "{\"action\":\"recruit\",\"id\":\"infantry\",\"count\":999999999999}", GameplayViewService.Preview.class));
        var request = json.readValue("{\"action\":\"recruit\",\"id\":\"infantry\",\"count\":2,\"cost\":0,\"allowed\":true,\"gain\":99999999}", GameplayViewService.Preview.class);
        var player = createTestPlayer();
        var quote = views.preview(player.getId(), request);
        assertNotEquals(0, quote.get("cost"));
        assertEquals(false, quote.get("allowed"));
    }

    @SuppressWarnings("unchecked")
    @Test void officerDispatchAndBattleDefaultsEligibilityFollowServerState() {
        var player = createTestPlayer();
        Officer officer = new Officer(); officer.setPlayerId(player.getId()); officer.setCitySlot(0);
        officer.setName("随队将领"); officer.setRole("idle"); officer.setStar(1); officer.setLevel(1);
        officer.setExp(0L); officerRepository.saveAndFlush(officer);
        var state = gameStateService.getGameState(player.getId());
        var gameplay = (Map<String, Object>) state.get("gameplay");
        var list = (List<Map<String, Object>>) state.get("officers");
        assertEquals(true, ((Map<?, ?>) list.get(0).get("dispatch")).get("allowed"));
        assertEquals(false, gameplay.get("battleDefaultsLocked"));
        officer.setRole("mayor"); officerRepository.saveAndFlush(officer);
        state = gameStateService.getGameState(player.getId());
        list = (List<Map<String, Object>>) state.get("officers");
        assertEquals(false, ((Map<?, ?>) list.get(0).get("dispatch")).get("allowed"));
        assertEquals(OfficerService.dispatchReason("mayor", false), ((Map<?, ?>) list.get(0).get("dispatch")).get("reason"));
        assertFalse(OfficerService.dispatchReason("idle", true).isEmpty());
        assertFalse(OfficerService.dispatchReason("march", false).isEmpty());
        officer.setRole("idle"); officerRepository.saveAndFlush(officer);
        var march = createMarch(player.getId(), "wild", "1", "目标", 0, 0, 1, 1,
                Map.of("infantry", 1), "conquer", com.wargame.util.GameClock.now(),
                com.wargame.util.GameClock.now() + 60000, false, false);
        march.setCommanderId(officer.getId()); marchRepository.saveAndFlush(march);
        state = gameStateService.getGameState(player.getId());
        list = (List<Map<String, Object>>) state.get("officers");
        assertEquals(false, ((Map<?, ?>) list.get(0).get("dispatch")).get("allowed"));
        assertEquals(true, ((Map<?, ?>) state.get("gameplay")).get("battleDefaultsLocked"));
        var access = (Map<String, Object>) gameplay.get("campaignAccess");
        assertFalse(access.isEmpty());
        access.values().forEach(entry -> assertEquals(false, ((Map<?, ?>) entry).get("allowed")));
    }

    @Test void accelerationSnapshotUsesTheSameStageLimitsAsOrderExecution() {
        long now = 1000000;
        March march = new March(); march.setStartAt(now - 10000); march.setArriveAt(now + 30001);
        var quote = MarchService.accelerationView(march, now);
        assertEquals(true, quote.get("allowed")); assertEquals(now + 1, quote.get("expiresAt"));
        assertEquals(false, MarchService.accelerationView(march, now + 1).get("allowed"));
        march.setInterceptDeployAt(now + 10001);
        quote = MarchService.accelerationView(march, now);
        assertEquals(true, quote.get("allowed")); assertEquals(10000L, quote.get("minRemainingMs"));
        assertEquals(false, MarchService.accelerationView(march, now + 1).get("allowed"));
        march.setOutboundAccelerated(true);
        assertEquals("本段行军已经加速", MarchService.accelerationView(march, now).get("reason"));
        march.setOutboundAccelerated(false); march.setGathering(true);
        assertEquals(false, MarchService.accelerationView(march, now).get("allowed"));
        march.setReturning(true); march.setInterceptDeployAt(null); march.setArriveAt(now + 60000);
        assertEquals(true, MarchService.accelerationView(march, now).get("allowed"));
        march.setDisplaced(true);
        assertEquals(false, MarchService.accelerationView(march, now).get("allowed"));
    }
}
