package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.WorldViewService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterceptionBattleTest extends BaseServiceTest {
    @Autowired PlayerItemRepository items;
    @Autowired WorldViewService views;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;
    private Long attackerId;
    private Long defenderId;
    private PlayerCity target;
    private long now;

    @BeforeEach
    void setUp() {
        attackerId = createTestPlayer("intercept-attacker", 30).getId();
        defenderId = createTestPlayer("intercept-defender", 30).getId();
        target = new PlayerCity();
        target.setWorldId(createTestWorld().getId());
        target.setOwnerId(defenderId);
        target.setName("侦查原目标");
        target.setX(10);
        target.setY(10);
        target.setCitySlot(0);
        target = playerCityRepository.save(target);
        now = GameClock.now();
    }

    private March incoming(Map<String, Integer> army) {
        March march = createMarch(attackerId, "player", target.getId().toString(), target.getName(),
                0, 0, 10, 10, army, "scout", now - 30_000L, now + 60_000L, false, false);
        march.setRouteMode("air");
        march.setRouteData("[{\"x\":0,\"y\":0},{\"x\":10,\"y\":10}]");
        march.setBaseDurationMs(90_000L);
        return marchRepository.saveAndFlush(march);
    }

    private March intercept(March enemy, Map<String, Integer> army) {
        March march = createMarch(defenderId, "incoming_march", enemy.getId().toString(), "城外拦截",
                10, 10, 5, 5, army, "intercept_scout", now - 10_000L, now, false, false);
        return marchRepository.saveAndFlush(march);
    }

    @Test
    void interceptionSendsBothPerspectivesAndDoesNotTouchAnotherIncomingMarch() {
        March enemy = incoming(Map.of("scout", 1));
        March untouched = incoming(Map.of("scout", 2));
        Officer commander = createOfficer(attackerId, "march", 0, 0, 0);
        enemy.setCommanderId(commander.getId());
        marchRepository.saveAndFlush(enemy);
        March interceptor = intercept(enemy, Map.of("fighter", 100));

        marchService.processMarches(defenderId, now);

        assertTrue(marchRepository.findById(enemy.getId()).isEmpty());
        assertEquals("idle", officerRepository.findById(commander.getId()).orElseThrow().getRole());
        assertEquals(Map.of("scout", 2), JsonUtil.parseIntMap(marchRepository.findById(untouched.getId()).orElseThrow().getArmy()));
        assertTrue(marchRepository.findById(interceptor.getId()).orElseThrow().getReturning());
        Map<String, Object> defense = report(defenderId);
        Map<String, Object> attack = report(attackerId);
        assertEquals("defender", defense.get("perspective"));
        assertEquals(true, defense.get("win"));
        assertEquals("拦截成功 · 城外截击", defense.get("subject"));
        assertEquals("attacker", attack.get("perspective"));
        assertEquals(false, attack.get("win"));
        assertEquals("突围失败 · 城外截击", attack.get("subject"));
        assertEquals("destroyed", attack.get("troopDisposition"));
        assertEquals("returning", defense.get("troopDisposition"));
        assertEquals(attack.get("initialAttacker"), defense.get("initialAttacker"));
        assertEquals(attack.get("survivorDefender"), defense.get("survivorDefender"));
        assertEquals("5,5", attack.get("interceptCoord"));
        assertEquals("10,10", attack.get("toCoord"));
        assertFalse(attack.containsKey("showCityInfo"));
        assertTrue(reportEntity(attackerId).getReadAt() == 0L);
        marchService.processMarches(defenderId, now);
        assertEquals(1, scoutReportRepository.findByPlayerId(attackerId).size());
        assertEquals(1, scoutReportRepository.findByPlayerId(defenderId).size());
    }

    @Test
    void breakthroughKeepsOriginalRouteDestinationAndArrivalThenExecutesRecon() {
        March enemy = incoming(Map.of("scout", 10_000));
        Player attacker = playerRepository.findById(attackerId).orElseThrow();
        attacker.setOutgoingBattleActions("{\"scout\":\"ADVANCE\"}");
        playerRepository.save(attacker);
        March interceptor = intercept(enemy, Map.of("transport", 1));
        String route = enemy.getRouteData();
        Long arrival = enemy.getArriveAt();
        Long start = enemy.getStartAt();
        Long baseDuration = enemy.getBaseDurationMs();
        Double distance = enemy.getDistance();
        String cargo = enemy.getCarryRes();

        marchService.processMarches(defenderId, now);

        March continuing = marchRepository.findById(enemy.getId()).orElseThrow();
        assertFalse(Boolean.TRUE.equals(continuing.getReturning()));
        assertEquals(route, continuing.getRouteData());
        assertEquals("air", continuing.getRouteMode());
        assertEquals(arrival, continuing.getArriveAt());
        assertEquals(start, continuing.getStartAt());
        assertEquals(baseDuration, continuing.getBaseDurationMs());
        assertEquals(distance, continuing.getDistance());
        assertEquals(cargo, continuing.getCarryRes());
        assertNull(continuing.getBattleId());
        assertEquals(target.getId().toString(), continuing.getTargetId());
        assertEquals(10, continuing.getTargetX());
        assertEquals(10, continuing.getTargetY());
        assertEquals("scout", continuing.getAction());
        assertTrue(JsonUtil.parseIntMap(continuing.getArmy()).get("scout") > 0);
        assertEquals(true, report(attackerId).get("win"));
        assertEquals(false, report(defenderId).get("win"));
        assertEquals("continuing", report(attackerId).get("troopDisposition"));
        assertTrue(marchRepository.findById(interceptor.getId()).isEmpty());

        marchService.processMarches(attackerId, arrival);
        assertTrue(scoutReportRepository.findByPlayerId(attackerId).stream().anyMatch(r -> "scout".equals(r.getType())));
        assertTrue(marchRepository.findById(enemy.getId()).orElseThrow().getReturning());
    }

    @Test
    void cancelledTargetDoesNotRedirectInterceptionToAnotherScout() {
        March enemy = incoming(Map.of("scout", 1));
        March untouched = incoming(Map.of("scout", 3));
        March interceptor = intercept(enemy, Map.of("fighter", 5));
        marchRepository.delete(enemy);
        marchRepository.flush();

        marchService.processMarches(defenderId, now);

        assertTrue(marchRepository.findById(interceptor.getId()).orElseThrow().getReturning());
        assertTrue(marchRepository.findById(untouched.getId()).isPresent());
        assertTrue(scoutReportRepository.findByPlayerId(defenderId).isEmpty());
    }

    @Test
    void dispatchTracksExactIncomingSourceAndOnlyDeductsAvailableTroops() {
        March enemy = incoming(Map.of("scout", 1));
        createArmyUnit(defenderId, "fighter", 5);
        March interceptor = marchService.createDispatch(defenderId, new DispatchRequest("incoming_march", enemy.getId(),
                "intercept_scout", Map.of("fighter", 2), null, null));
        assertEquals("incoming_march", interceptor.getTargetKind());
        assertEquals(enemy.getId().toString(), interceptor.getTargetId());
        assertEquals(3, armyUnitRepository.findByPlayerIdAndType(defenderId, "fighter").get(0).getCount());
        assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(defenderId,
                new DispatchRequest("incoming_march", enemy.getId(), "intercept_scout", Map.of("fighter", 4), null, null)));
    }

    @Test
    void ordinaryPlunderMarchCanBeInterceptedWithGroundTroops() {
        March enemy = incoming(Map.of("infantry", 100));
        enemy.setAction("plunder");
        marchRepository.saveAndFlush(enemy);
        createArmyUnit(defenderId, "infantry", 12);

        March interceptor = marchService.createDispatch(defenderId, new DispatchRequest("incoming_march", enemy.getId(),
                "intercept_scout", Map.of("infantry", 7), null, null));

        assertEquals("incoming_march", interceptor.getTargetKind());
        assertEquals(enemy.getId().toString(), interceptor.getTargetId());
        assertEquals(Map.of("infantry", 7), JsonUtil.parseIntMap(interceptor.getArmy()));
        assertEquals(5, armyUnitRepository.findByPlayerIdAndType(defenderId, "infantry").get(0).getCount());
    }

    @Test
    void realScoutMarchRejectsInfantryButAllowsArmoredCars() {
        March enemy = incoming(Map.of("scout", 1));
        createArmyUnit(defenderId, "infantry", 12);
        createArmyUnit(defenderId, "armored", 5);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                marchService.createDispatch(defenderId, new DispatchRequest("incoming_march", enemy.getId(),
                        "intercept_scout", Map.of("infantry", 7), null, null)));
        assertEquals("侦查拦截仅支持空军或装甲车", error.getMessage());
        assertEquals(12, armyUnitRepository.findByPlayerIdAndType(defenderId, "infantry").get(0).getCount());
        assertTrue(marchRepository.findByPlayerId(defenderId).isEmpty());

        March interceptor = marchService.createDispatch(defenderId, new DispatchRequest("incoming_march", enemy.getId(),
                "intercept_scout", Map.of("armored", 2), null, null));
        assertEquals(Map.of("armored", 2), JsonUtil.parseIntMap(interceptor.getArmy()));
        assertEquals(3, armyUnitRepository.findByPlayerIdAndType(defenderId, "armored").get(0).getCount());
    }

    @Test
    void missingRealMarchNeverFallsBackToIncomingTableWithTheSameId() {
        IncomingMarch legacy = new IncomingMarch();
        legacy.setTargetPlayerId(defenderId);
        legacy.setFromName("模拟侦查");
        legacy.setArmy(JsonUtil.toJson(Map.of("scout", 1)));
        legacy.setAction("scout");
        legacy.setArriveAt(now + 60_000L);
        legacy = incomingMarchRepository.saveAndFlush(legacy);
        createArmyUnit(defenderId, "fighter", 5);
        Long id = legacy.getId();
        assertTrue(marchRepository.findById(id).isEmpty());

        assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(defenderId,
                new DispatchRequest("incoming_march", id, "intercept_scout", Map.of("fighter", 1), null, null)));

        assertTrue(incomingMarchRepository.findById(id).isPresent());
        assertEquals(5, armyUnitRepository.findByPlayerIdAndType(defenderId, "fighter").get(0).getCount());
    }

    @Test
    void interceptionLossesRecoverInTheOriginalCityWithoutReturningDestroyedTroops() {
        March enemy = incoming(Map.of("scout", 10));
        createTechnology(attackerId, "log_medical", 10);
        intercept(enemy, Map.of("fighter", 100));

        marchService.processMarches(defenderId, now);

        assertTrue(marchRepository.findById(enemy.getId()).isEmpty());
        assertEquals(10, report(attackerId).get("losses"));
        assertEquals(50, report(attackerId).get("recoveryPercent"));
        assertEquals(5, report(attackerId).get("recoveredCount"));
        assertTrue(armyUnitRepository.findByPlayerIdAndType(attackerId, "scout").isEmpty());
    }

    /** 建立与真实派遣相同的持久化部署，精确推进游戏时间验证到位和交火不是同一时刻。 */
    private March deployed(March enemy, Map<String, Integer> army, long clashAt, boolean prepared) {
        March interceptor = intercept(enemy, army);
        interceptor.setStartAt(now - 10_000L);
        interceptor.setInterceptDeployAt(prepared ? clashAt - 5_000L : clashAt - 1_000L);
        interceptor.setInterceptReadyAt(interceptor.getInterceptDeployAt() + 5_000L);
        interceptor.setArriveAt(clashAt);
        interceptor.setInterceptEnemyProgress((double) (clashAt - enemy.getStartAt()) / (enemy.getArriveAt() - enemy.getStartAt()));
        interceptor.setInterceptEnemyStartAt(enemy.getStartAt());
        interceptor.setInterceptRouteSnapshot(com.wargame.service.InterceptionPlanner.signature(enemy));
        interceptor.setBaseDurationMs(interceptor.getInterceptDeployAt() - interceptor.getStartAt());
        return marchRepository.saveAndFlush(interceptor);
    }

    @Test
    void deploymentAndPreparationDoNotFireEarlyAndBothReportsKeepIdenticalOpening() {
        March enemy = incoming(Map.of("scout", 10));
        March interceptor = deployed(enemy, Map.of("fighter", 100), now + 10_000L, true);
        marchService.processMarches(defenderId, interceptor.getInterceptDeployAt());
        marchService.processMarches(defenderId, interceptor.getInterceptReadyAt() - 1);
        assertEquals(Map.of("scout", 10), JsonUtil.parseIntMap(enemy.getArmy()));
        assertTrue(scoutReportRepository.findByPlayerId(defenderId).isEmpty());
        marchService.processMarches(defenderId, interceptor.getArriveAt());
        assertEquals(report(attackerId).get("interceptionOpening"), report(defenderId).get("interceptionOpening"));
        assertEquals("ambush", ((Map<?, ?>) report(defenderId).get("interceptionOpening")).get("mode"));
        marchService.processMarches(defenderId, now + 10_000L);
        assertEquals(1, scoutReportRepository.findByPlayerId(defenderId).size());
    }

    @Test
    void sameTimeInterceptorsConsumeOneAmbushAndAttackerContinuesOriginalRoute() {
        March enemy = incoming(Map.of("scout", 10_000));
        long arrival = enemy.getArriveAt();
        String route = enemy.getRouteData();
        March first = deployed(enemy, Map.of("fighter", 1), now + 5_000L, true);
        March second = deployed(enemy, Map.of("fighter", 1), now + 5_000L, true);
        marchService.processMarches(attackerId, now + 5_000L);
        var reports = scoutReportRepository.findByPlayerId(attackerId);
        assertEquals(2, reports.size());
        assertEquals(1, reports.stream().map(r -> (Map<?, ?>) JsonUtil.parseObjMap(r.getData()).get("interceptionOpening"))
                .filter(o -> "ambush".equals(o.get("mode"))).count());
        var firstOpening = scoutReportRepository.findByPlayerId(defenderId).stream().map(r -> JsonUtil.parseObjMap(r.getData()))
                .filter(r -> "ambush".equals(((Map<?, ?>) r.get("interceptionOpening")).get("mode"))).findFirst().orElseThrow();
        assertEquals(first.getId().longValue(), ((Number) firstOpening.get("marchId")).longValue());
        March survivor = marchRepository.findById(enemy.getId()).orElseThrow();
        assertTrue(survivor.getAmbushUsed());
        assertFalse(survivor.getReturning());
        assertEquals(arrival, survivor.getArriveAt());
        assertEquals(route, survivor.getRouteData());
        assertEquals(target.getId().toString(), survivor.getTargetId());
    }

    @Test
    void emergencyDoesNotConsumeAmbushAndLaterPreparedInterceptionCanUseIt() {
        March enemy = incoming(Map.of("scout", 10_000));
        deployed(enemy, Map.of("fighter", 1), now + 5_000L, false);
        deployed(enemy, Map.of("fighter", 1), now + 8_000L, true);
        marchService.processMarches(attackerId, now + 5_000L);
        assertFalse(marchRepository.findById(enemy.getId()).orElseThrow().getAmbushUsed());
        marchService.processMarches(attackerId, now + 8_000L);
        assertTrue(marchRepository.findById(enemy.getId()).orElseThrow().getAmbushUsed());
    }

    @Test
    void lateAttackerTickResolvesInterceptionBeforeStartingCityTask() {
        March enemy = incoming(Map.of("scout", 10));
        deployed(enemy, Map.of("fighter", 100), now + 10_000L, true);
        marchService.processMarches(attackerId, enemy.getArriveAt() + 1);
        assertTrue(marchRepository.findById(enemy.getId()).isEmpty());
        assertTrue(scoutReportRepository.findByPlayerId(attackerId).stream().allMatch(r -> "battle".equals(r.getType())));
    }

    @Test
    void changedTimelineReschedulesClashAndCanRemovePreparationOrCancelDeployment() {
        March enemy = incoming(Map.of("scout", 10));
        March interceptor = deployed(enemy, Map.of("fighter", 100), now + 10_000L, true);
        long oldClash = interceptor.getArriveAt();
        enemy.setArriveAt(enemy.getArriveAt() + 30_000L);
        marchRepository.saveAndFlush(enemy);
        marchService.processMarches(defenderId, now);
        assertTrue(interceptor.getArriveAt() > oldClash);
        assertTrue(scoutReportRepository.findByPlayerId(defenderId).isEmpty());
        // 敌军过早穿过已选位置，拦截队不能瞬移补上。
        enemy.setArriveAt(now + 1_000L);
        marchRepository.saveAndFlush(enemy);
        marchService.processMarches(defenderId, now);
        assertTrue(interceptor.getReturning());
        assertFalse(enemy.getAmbushUsed());
        assertTrue(scoutReportRepository.findByPlayerId(defenderId).isEmpty());
    }

    @Test
    void recallingEnemyOrChangingRouteCancelsWaitingAmbushWithoutConsumingIt() {
        March enemy = incoming(Map.of("scout", 10));
        March interceptor = deployed(enemy, Map.of("fighter", 100), now + 10_000L, true);
        enemy.setReturning(true);
        marchRepository.saveAndFlush(enemy);
        marchService.processMarches(attackerId, now);
        assertTrue(interceptor.getReturning());
        assertFalse(enemy.getAmbushUsed());
    }

    @Test
    void previewAndDispatchIgnoreForgedCoordinatesAndUseSamePreparationRule() {
        March enemy = incoming(Map.of("scout", 10));
        createArmyUnit(defenderId, "fighter", 5);
        DispatchRequest request = new DispatchRequest("incoming_march", enemy.getId(), "intercept_scout", Map.of("fighter", 2),
                null, null, null, null, null, null, null, 499, 499);
        var preview = marchService.previewRoute(defenderId, request);
        March interceptor = marchService.createDispatch(defenderId, request);
        assertNotEquals(499, interceptor.getTargetX());
        assertNotEquals(499, interceptor.getTargetY());
        assertEquals(5_000L, interceptor.getInterceptReadyAt() - interceptor.getInterceptDeployAt());
        assertEquals("ambush", preview.get("opening"));
        assertTrue(interceptor.getInterceptReadyAt() <= interceptor.getArriveAt());
        assertEquals(0, ((Number) preview.get("reservedLoad")).intValue());
    }

    @Test
    void accelerationCanRemovePreparationAndDelayCanRestoreItWithoutMovingDeployment() throws Exception {
        try (var clock = GameClock.use(() -> now)) {
            March enemy = incoming(Map.of("scout", 10_000));
            March interceptor = deployed(enemy, Map.of("fighter", 1), now + 10_000L, true);
            interceptor.setInterceptDeployAt(now + 2_000L); interceptor.setInterceptReadyAt(now + 7_000L);
            marchRepository.saveAndFlush(interceptor);
            long deploy = interceptor.getInterceptDeployAt();
            items.saveAndFlush(new PlayerItem(null, attackerId, "marchOrd", 1, now));
            marchService.speedUpMarch(attackerId, enemy.getId(), false, enemy.getVersion());
            interceptor = marchRepository.findById(interceptor.getId()).orElseThrow();
            assertEquals(deploy, interceptor.getInterceptDeployAt());
            assertTrue(interceptor.getArriveAt() < interceptor.getInterceptReadyAt());
            items.saveAndFlush(new PlayerItem(null, defenderId, "signalJammingOrder", 1, now));
            enemy = marchRepository.findById(enemy.getId()).orElseThrow();
            marchService.applyMarchStratagem(defenderId, enemy.getId(), "signalJammingOrder", enemy.getArriveAt());
            interceptor = marchRepository.findById(interceptor.getId()).orElseThrow();
            assertTrue(interceptor.getArriveAt() >= interceptor.getInterceptReadyAt());
            assertEquals(deploy, interceptor.getInterceptDeployAt());
            assertTrue(scoutReportRepository.findByPlayerId(defenderId).isEmpty());
        }
    }

    @Test
    void acceleratingInterceptorShortensDeploymentButDoesNotMoveEnemyCrossing() throws Exception {
        try (var clock = GameClock.use(() -> now)) {
            March enemy = incoming(Map.of("scout", 10_000));
            March interceptor = deployed(enemy, Map.of("fighter", 1), now + 19_000L, false);
            interceptor.setInterceptDeployAt(now + 15_000L); interceptor.setInterceptReadyAt(now + 20_000L);
            marchRepository.saveAndFlush(interceptor);
            items.saveAndFlush(new PlayerItem(null, defenderId, "marchOrd", 1, now));
            marchService.speedUpMarch(defenderId, interceptor.getId(), false, interceptor.getVersion());
            March updated = marchRepository.findById(interceptor.getId()).orElseThrow();
            assertEquals(now + 19_000L, updated.getArriveAt());
            assertEquals(now + 10_000L, updated.getInterceptDeployAt());
            assertEquals(now + 15_000L, updated.getInterceptReadyAt());
        }
    }

    @Test
    void cancelledAmbushKeepsAllowanceAndWaitingTimeDoesNotExtendReturnTrip() throws Exception {
        try (var clock = GameClock.use(() -> now)) {
            March enemy = incoming(Map.of("scout", 10));
            March interceptor = deployed(enemy, Map.of("fighter", 1), now + 10_000L, true);
            interceptor.setInterceptDeployAt(now - 5_000L); interceptor.setInterceptReadyAt(now);
            interceptor.setBaseDurationMs(5_000L); marchRepository.saveAndFlush(interceptor);
            var outcome = marchService.cancelMarch(defenderId, interceptor.getId());
            assertEquals(true, outcome.get("success"));
            March returning = marchRepository.findById(interceptor.getId()).orElseThrow();
            assertTrue(returning.getReturning());
            assertEquals(now + 5_000L, returning.getArriveAt());
            assertFalse(enemy.getAmbushUsed());
            assertTrue(scoutReportRepository.findByPlayerId(defenderId).isEmpty());
        }
    }

    @Test
    void lateRecallSettlesAlreadyCrossedAmbushAndDoesNotRollbackItsReports() throws Exception {
        try (var clock = GameClock.use(() -> now)) {
            March enemy = incoming(Map.of("scout", 10));
            March interceptor = deployed(enemy, Map.of("fighter", 100), now - 1_000L, true);
            var outcome = marchService.cancelMarch(attackerId, enemy.getId());
            assertEquals(false, outcome.get("success"));
            assertTrue(marchRepository.findById(enemy.getId()).isEmpty());
            assertTrue(marchRepository.findById(interceptor.getId()).orElseThrow().getReturning());
            assertEquals(1, scoutReportRepository.findByPlayerId(attackerId).size());
        }
    }

    @Test
    void persistedDeploymentSurvivesReloadAndUnadvancedGameTimeDoesNotFireIt() {
        March enemy = incoming(Map.of("scout", 10));
        March interceptor = deployed(enemy, Map.of("fighter", 100), now + 10_000L, true);
        Long id = interceptor.getId();
        em.flush(); em.clear();
        March restored = marchRepository.findById(id).orElseThrow();
        assertEquals(now + 5_000L, restored.getInterceptDeployAt());
        marchService.processMarches(defenderId, now);
        marchService.processMarches(defenderId, now);
        assertTrue(scoutReportRepository.findByPlayerId(defenderId).isEmpty());
        marchService.processMarches(defenderId, now + 10_000L);
        assertEquals(1, scoutReportRepository.findByPlayerId(defenderId).size());
    }

    @Test
    void consumedEnemyShowsUrgentInOwnPreviewAndOwnMapStatus() {
        March enemy = incoming(Map.of("scout", 10)); enemy.setAmbushUsed(true); marchRepository.saveAndFlush(enemy);
        createArmyUnit(defenderId, "fighter", 5);
        var request = new DispatchRequest("incoming_march", enemy.getId(), "intercept_scout", Map.of("fighter", 1), null, null);
        assertEquals("urgent", marchService.previewRoute(defenderId, request).get("opening"));
        March interceptor = marchService.createDispatch(defenderId, request);
        var own = (java.util.List<Map<String, Object>>) views.getWorld(defenderId, 0, 0, 1).get("marches");
        assertEquals("urgent", own.stream().filter(m -> interceptor.getId().equals(m.get("id"))).findFirst().orElseThrow().get("interceptionOpening"));
    }

    private ScoutReport reportEntity(Long recipientId) {
        return scoutReportRepository.findByPlayerId(recipientId).stream().filter(r -> "battle".equals(r.getType())).findFirst().orElseThrow();
    }

    private Map<String, Object> report(Long recipientId) {
        return JsonUtil.parseObjMap(reportEntity(recipientId).getData());
    }
}
