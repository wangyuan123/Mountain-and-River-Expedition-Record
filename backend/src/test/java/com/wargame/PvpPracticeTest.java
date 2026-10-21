package com.wargame;

import com.wargame.model.constants.*;
import com.wargame.model.dto.pvp.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.BattleService;
import com.wargame.service.UnitEquipmentService;
import com.wargame.service.pvp.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PvpPracticeTest extends BaseServiceTest {
    @Autowired PvpPracticeService pvp;
    @Autowired PvpMatchService matcher;
    @Autowired PvpSnapshotService snapshots;
    @Autowired PvpPowerModel model;
    @Autowired PvpPracticeSessionRepository sessions;
    @Autowired PvpMatchTicketRepository tickets;
    @Autowired PvpPlayerStateRepository states;
    @Autowired PvpRoundResultRepository rounds;
    @Autowired PvpPracticeReportRepository reports;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    private String id() { return UUID.randomUUID().toString(); }
    private Long player() { Long p = createTestPlayer("pvp-" + id(), 30).getId(); createArmyUnit(p, "infantry", 100); return p; }
    private PvpSnapshot snap(Long player) {
        var preset = (PvpPreset) pvp.preset(player).get("preset");
        return model.estimate(snapshots.freeze(player, new PvpPreset(0, "LAND_AIR", Map.of("infantry", 100), null, preset.defaults())));
    }
    private String enqueue(Long player) {
        var snapshot = snap(player); return (String) pvp.enqueue(player, id(), 0, snapshot, Map.of("queue", player)).get("ticketId");
    }

    @Test void fireDensityIsFrozenInNewSnapshotsAndRaisesPowerWithoutChangingOldOnes() {
        Long player = player();
        var before = snap(player);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> createTechnology(player, "fire_density", 10));
        var after = snap(player);
        assertEquals(10, after.tech().get("fire_density"));
        assertFalse(before.tech().containsKey("fire_density"));
        assertEquals(before.effectiveStats().get("infantry").get("ground") * 1.5,
                after.effectiveStats().get("infantry").get("ground"), 1e-9);
        assertTrue(after.power() > before.power());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var technology = technologyRepository.findByPlayerIdAndType(player, "fire_density").get(0);
            technology.setLevel(0); technologyRepository.save(technology);
        });
        assertEquals(10, after.tech().get("fire_density"));
    }
    private void update(String session, java.util.function.Consumer<PvpPracticeSession> action) {
        new TransactionTemplate(transactions).executeWithoutResult(t -> { var s = sessions.findById(session).orElseThrow(); action.accept(s); sessions.save(s); });
    }
    private String battle(Long a, Long b) {
        String ta = enqueue(a), tb = enqueue(b);
        if (!pvp.pair(ta, tb)) {
            new TransactionTemplate(transactions).executeWithoutResult(t -> {
                for (String id : List.of(ta, tb)) { var ticket = tickets.findById(id).orElseThrow(); ticket.setQueuedAt(System.currentTimeMillis() - 120000); tickets.save(ticket); }
            });
            assertTrue(pvp.pair(ta, tb));
        }
        String session = (String) pvp.status(a).get("sessionId");
        pvp.accept(a, id(), ta, session, Map.of("accept", a)); pvp.accept(b, id(), tb, session, Map.of("accept", b));
        pvp.loaded(a, id(), session, Map.of("loaded", a)); pvp.loaded(b, id(), session, Map.of("loaded", b));
        update(session, s -> { s.setStartsAt(System.currentTimeMillis() - 1); s.setDeadlineAt(s.getStartsAt()); }); pvp.advance(session);
        return session;
    }

    @Test void outageDoesNotExpireBattleOrSkipRoundAndResumesAfterRemainingGameTime() throws Exception {
        Long a = player(), b = player();
        String session = battle(a, b);
        long realDeadline = sessions.findById(session).orElseThrow().getDeadlineAt();
        var time = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() - 2 * 86_400_000L);
        update(session, s -> s.setDeadlineAt(time.get() + 15_000));
        try (var ignored = com.wargame.util.GameClock.use(time::get)) {
            pvp.advance(session); pvp.advance(session);
            assertEquals("BATTLING", pvp.battle(a, session).get("status"));
            assertEquals(1, pvp.battle(a, session).get("round"));
            assertTrue(rounds.findBySessionIdOrderByRoundNoAsc(session).isEmpty());
            assertEquals(time.get(), pvp.status(a).get("serverTime"));
            time.addAndGet(15_000); pvp.advance(session);
            assertEquals(2, pvp.battle(a, session).get("round"));
            assertEquals(1, rounds.findBySessionIdOrderByRoundNoAsc(session).size());
        } finally {
            update(session, s -> s.setDeadlineAt(realDeadline));
            pvp.surrender(a, id(), session, Map.of("end", 1));
        }
    }
    private List<Map<String, Object>> world(Long player) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String table : List.of("players", "resources", "army_units", "officers", "officer_equipment", "player_items", "marches", "unit_loadouts", "unit_equipment_orders", "fortifications", "buildings", "technologies", "wounded_units", "prisoner_units", "player_quests", "player_quest_stages", "player_cities", "battle_reports")) {
            boolean exists = jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name) = ?", Integer.class, table) > 0;
            if (!exists) continue;
            String owner = table.equals("players") ? "id" : table.equals("player_cities") ? "owner_id" : "player_id";
            out.add(Map.of("table", table, "rows", jdbc.queryForList("select * from " + table + " where " + owner + " = ? order by id", player)));
        }
        return out;
    }

    @Test void fullDefaultBattleUsesOnlyCopiesAndSettlesBothExactlyOnce() {
        Long a = player(), b = player(); var beforeA = world(a); var beforeB = world(b);
        String session = battle(a, b);
        for (int i = 0; i < 24 && !"FINISHED".equals(pvp.battle(a, session).get("status")); i++) {
            update(session, s -> s.setDeadlineAt(System.currentTimeMillis() - 1)); pvp.advance(session);
        }
        var result = pvp.report(a, session);
        assertEquals("PVP_PRACTICE", result.get("mode")); assertEquals(0, ((Number) result.get("actualLoss")).intValue());
        assertTrue(((List<?>) result.get("rounds")).size() > 0); assertEquals("IDLE", pvp.status(a).get("status"));
        assertEquals("IDLE", pvp.status(b).get("status")); assertEquals(1, states.findById(a).orElseThrow().getGames());
        int oldR = states.findById(a).orElseThrow().getRating(); pvp.advance(session); pvp.surrender(a, id(), session, Map.of("after", 1));
        assertEquals(oldR, states.findById(a).orElseThrow().getRating()); assertEquals(1, pvp.history(a, 0).size());
        assertEquals(beforeA, world(a)); assertEquals(beforeB, world(b));
    }

    @Test void draftsArePrivateOneLockDoesNotAdvanceAndExpiredWritesAreRejected() {
        Long a = player(), b = player(), stranger = player(); String session = battle(a, b);
        String request = id(); var manual = Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.RETREAT));
        Object body = Map.of("command", 1);
        var first = pvp.submit(a, request, session, 1, 1, manual, false, body);
        assertEquals(com.wargame.util.JsonUtil.parseTree(PvpJson.write(first)), com.wargame.util.JsonUtil.parseTree(PvpJson.write(pvp.submit(a, request, session, 1, 1, manual, false, body))));
        assertThrows(IllegalArgumentException.class, () -> pvp.submit(a, request, session, 1, 2, manual, false, Map.of("command", 2)));
        assertThrows(IllegalArgumentException.class, () -> pvp.submit(a, id(), session, 1, 1, manual, false, body));
        var enemy = pvp.battle(b, session); assertEquals(Map.of(), enemy.get("draft")); assertFalse(((Map<?, ?>) enemy.get("enemy")).containsKey("defaults"));
        pvp.submit(a, id(), session, 1, 2, manual, true, Map.of("locked", 1));
        assertEquals(1, pvp.battle(a, session).get("round")); assertEquals(true, pvp.battle(b, session).get("enemyReady"));
        assertThrows(IllegalArgumentException.class, () -> pvp.battle(stranger, session));
        update(session, s -> s.setDeadlineAt(System.currentTimeMillis() - 1));
        assertThrows(IllegalArgumentException.class, () -> pvp.submit(b, id(), session, 1, 1, Map.of(), true, body));
        pvp.advance(session); assertEquals(2, pvp.battle(a, session).get("round")); assertEquals(1, rounds.findBySessionIdOrderByRoundNoAsc(session).size());
        var saved = PvpJson.read(rounds.findBySessionIdOrderByRoundNoAsc(session).get(0).getData(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        String ownSide = (String) pvp.battle(a, session).get("side"); assertTrue(saved.get("orders" + ownSide).toString().contains("RETREAT"));
        assertThrows(IllegalArgumentException.class, () -> pvp.report(stranger, session));
        pvp.surrender(a, id(), session, Map.of("end", 1));
    }

    @Test void acceptanceTimeoutRequeuesOnlyAcceptedPlayerWithNewTicketAndPreservedWaitingTime() {
        Long a = player(), b = player(); String ta = enqueue(a), tb = enqueue(b); long origin = tickets.findById(ta).orElseThrow().getQueuedAt();
        assertTrue(pvp.pair(ta, tb)); String session = (String) pvp.status(a).get("sessionId");
        pvp.accept(a, id(), ta, session, Map.of("accept", 1)); update(session, s -> s.setDeadlineAt(System.currentTimeMillis() - 1)); pvp.advance(session);
        var status = pvp.status(a); assertEquals("QUEUED", status.get("status")); assertNotEquals(ta, status.get("ticketId")); assertEquals(origin, status.get("queuedAt"));
        assertEquals("IDLE", pvp.status(b).get("status"));
        assertThrows(IllegalArgumentException.class, () -> pvp.cancel(a, id(), ta, Map.of("old", 1)));
        assertThrows(IllegalArgumentException.class, () -> pvp.accept(a, id(), ta, session, Map.of("old", 2)));
        assertEquals(status.get("ticketId"), pvp.status(a).get("ticketId"));
    }

    @Test void frozenSnapshotSurvivesRealTroopChangesAndLoadFailureDoesNotRate() {
        Long a = player(), b = player(); String ta = enqueue(a), tb = enqueue(b);
        var troop = armyUnitRepository.findByPlayerIdAndCitySlotAndType(a, 0, "infantry").get(0); troop.setCount(1); armyUnitRepository.save(troop);
        assertEquals(100, PvpJson.read(tickets.findById(ta).orElseThrow().getSnapshot(), PvpSnapshot.class).army().get("infantry"));
        assertTrue(pvp.pair(ta, tb)); String session = (String) pvp.status(a).get("sessionId");
        pvp.accept(a, id(), ta, session, Map.of("accept", a)); pvp.accept(b, id(), tb, session, Map.of("accept", b));
        pvp.loaded(a, id(), session, Map.of("loaded", 1)); update(session, s -> s.setDeadlineAt(System.currentTimeMillis() - 1)); pvp.advance(session);
        assertEquals("VOID", pvp.report(a, session).get("result")); assertEquals(1000, states.findById(a).orElseThrow().getRating());
        assertEquals(0, states.findById(a).orElseThrow().getGames()); assertEquals(1, armyUnitRepository.findById(troop.getId()).orElseThrow().getCount());
    }

    @Test void concurrentEnqueueAndLocksHaveExactlyOneEffect() throws Exception {
        Long a = player(), b = player(); var sa = snap(a); var pool = Executors.newFixedThreadPool(2); var gate = new CyclicBarrier(2);
        Callable<Boolean> enqueue = () -> { gate.await(); try { pvp.enqueue(a, id(), 0, sa, Map.of("enqueue", id())); return true; } catch (IllegalArgumentException expected) { return false; } };
        try {
            var f1 = pool.submit(enqueue); var f2 = pool.submit(enqueue); assertEquals(1, (f1.get(20, TimeUnit.SECONDS) ? 1 : 0) + (f2.get(20, TimeUnit.SECONDS) ? 1 : 0));
            pvp.cancel(a, id(), (String) pvp.status(a).get("ticketId"), Map.of("cancel", 1));
            String session = battle(a, b); var barrier = new CyclicBarrier(2);
            Callable<Map<String, Object>> first = () -> { barrier.await(); return pvp.submit(a, id(), session, 1, 1, Map.of(), true, Map.of("a", 1)); };
            Callable<Map<String, Object>> second = () -> { barrier.await(); return pvp.submit(b, id(), session, 1, 1, Map.of(), true, Map.of("b", 1)); };
            var one = pool.submit(first); var two = pool.submit(second); one.get(30, TimeUnit.SECONDS); two.get(30, TimeUnit.SECONDS);
            assertEquals(2, pvp.battle(a, session).get("round")); assertEquals(1, rounds.findBySessionIdOrderByRoundNoAsc(session).size());
            pvp.surrender(a, id(), session, Map.of("surrender", 1));
        } finally { pool.shutdownNow(); }
    }

    @Test void oldTimelineRecoveryPreservesDraftAndDoesNotGiveFreshFifteenSeconds() {
        Long a = player(), b = player(); String session = battle(a, b);
        pvp.submit(a, id(), session, 1, 1, Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)), false, Map.of("draft", 1));
        long expired = System.currentTimeMillis() - 70000; update(session, s -> s.setDeadlineAt(expired)); pvp.advance(session);
        var s = sessions.findById(session).orElseThrow();
        if ("RECOVERING".equals(s.getStatus())) { assertEquals(expired + 60000, s.getDeadlineAt()); assertThrows(IllegalArgumentException.class, () -> pvp.submit(a, id(), session, s.getRoundNo(), 1, Map.of(), false, Map.of("late", 1))); pvp.advance(session); }
        assertFalse(sessions.findById(session).orElseThrow().getDeadlineAt() > System.currentTimeMillis() + 15000);
        pvp.surrender(a, id(), session, Map.of("surrender", 2));
    }

    @Test void fiveConsecutiveRepeatedOpponentsDisableSixthRatingOnly() {
        Long a = player(), b = player(); int ratingAfterFive = 0;
        for (int i = 0; i < 6; i++) {
            String session = battle(a, b); pvp.surrender(a, id(), session, Map.of("surrender", i));
            if (i == 4) ratingAfterFive = states.findById(a).orElseThrow().getRating();
            if (i == 5) assertEquals(false, pvp.report(a, session).get("rated"));
        }
        assertEquals(ratingAfterFive, states.findById(a).orElseThrow().getRating()); assertEquals(6, states.findById(a).orElseThrow().getGames());
    }

    @Test void defaultsAutoTakeoverAndReportRetentionDoNotPunishNoCommands() {
        Long a = player(), b = player(); String session = battle(a, b);
        var auto = pvp.auto(a, id(), session, 1, true, Map.of("auto", 1)); assertEquals(true, auto.get("locked"));
        pvp.auto(a, id(), session, 1, false, Map.of("takeover", 1));
        pvp.submit(b, id(), session, 1, 1, Map.of(), true, Map.of("lock", 1));
        var next = pvp.battle(a, session); assertEquals(false, next.get("auto")); assertEquals(false, next.get("locked"));
        pvp.surrender(a, id(), session, Map.of("surrender", 1));
        for (int i = 0; i < 52; i++) { PvpPracticeReport r = new PvpPracticeReport(); r.setId(id()); r.setSessionId(id()); r.setPlayerId(a); r.setCreatedAt(i); r.setResult("DRAW"); r.setData("{}"); reports.save(r); }
        String second = battle(a, b); pvp.surrender(a, id(), second, Map.of("surrender", 2));
        // 接口仅展示五十份；物理删除必须经过独立观察期开关。
        assertEquals(54, reports.findByPlayerIdOrderByCreatedAtDesc(a, org.springframework.data.domain.PageRequest.of(0, 100)).size());
        assertEquals(50, java.util.stream.IntStream.range(0, 5).map(i -> pvp.history(a, i).size()).sum());
        assertTrue(pvp.history(a, 5).isEmpty());
    }

    @Test void validationRejectsInvalidNumbersCitiesMixedDomainsAndOfficers() {
        Long a = player();
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("infantry", -1), Map.<String, Object>of("infantry", 1.5), Map.<String, Object>of("infantry", Double.NaN), Map.<String, Object>of("infantry", Long.MAX_VALUE), Map.<String, Object>of("fake", 1)))
            assertThrows(IllegalArgumentException.class, () -> PvpSnapshotService.quantities(invalid));
        var defaults = ((PvpPreset) pvp.preset(a).get("preset")).defaults();
        assertThrows(IllegalArgumentException.class, () -> snapshots.freeze(a, new PvpPreset(99, "LAND_AIR", Map.of("infantry", 1), null, defaults)));
        assertThrows(IllegalArgumentException.class, () -> snapshots.freeze(a, new PvpPreset(0, "LAND_AIR", Map.of("infantry", 101), null, defaults)));
        createArmyUnit(a, "destroyer", 1);
        assertThrows(IllegalArgumentException.class, () -> snapshots.freeze(a, new PvpPreset(0, "LAND_AIR", Map.of("infantry", 1, "destroyer", 1), null, defaults)));
        assertThrows(IllegalArgumentException.class, () -> snapshots.freeze(a, new PvpPreset(0, "LAND_AIR", Map.of("infantry", 1), 999999L, defaults)));
    }

    @Test void cancellationAndPairingRaceNeverCancelAReplacementTicketOrLeaveTwoOccupancies() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 3; i++) {
                Long a = player(), b = player(); String ta = enqueue(a), tb = enqueue(b); var gate = new CyclicBarrier(2);
                var pairing = pool.submit(() -> { gate.await(); return pvp.pair(ta, tb); });
                var cancel = pool.submit(() -> { gate.await(); try { pvp.cancel(a, id(), ta, Map.of("cancel", ta)); } catch (IllegalArgumentException conflict) { pvp.cancel(a, id(), ta, Map.of("retry", ta)); } return true; });
                pairing.get(20, TimeUnit.SECONDS); cancel.get(20, TimeUnit.SECONDS);
                assertEquals("IDLE", pvp.status(a).get("status"));
                assertTrue(Set.of("IDLE", "QUEUED").contains(pvp.status(b).get("status")));
                assertNull(states.findById(a).orElseThrow().getActiveSessionId());
                assertEquals(0, states.findById(a).orElseThrow().getGames());
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void threeConfirmationExitsTriggerOnlyDeclinersCooldown() {
        Long a = player(), b = player(); String ta = enqueue(a);
        for (int i = 0; i < 3; i++) {
            String tb = enqueue(b); assertTrue(pvp.pair(ta, tb)); String session = (String) pvp.status(a).get("sessionId");
            pvp.accept(a, id(), ta, session, Map.of("accept", i)); pvp.cancel(b, id(), tb, Map.of("decline", i));
            ta = (String) pvp.status(a).get("ticketId"); assertEquals("QUEUED", pvp.status(a).get("status"));
        }
        assertTrue(states.findById(b).orElseThrow().getCooldownUntil() > System.currentTimeMillis());
        assertEquals(0, states.findById(a).orElseThrow().getCooldownUntil());
        assertThrows(IllegalArgumentException.class, () -> enqueue(b));
        Long c = player();
        for (int i = 0; i < 4; i++) pvp.cancel(c, id(), enqueue(c), Map.of("ordinary", i));
        assertEquals(0, states.findById(c).orElseThrow().getCooldownUntil());
        Long d = player(), e = player(); String td = enqueue(d), te = enqueue(e); assertTrue(pvp.pair(td, te));
        pvp.cancel(d, id(), td, Map.of("earlyDecline", 1));
        assertEquals(1, states.findById(d).orElseThrow().getConfirmationExits());
        assertEquals(0, states.findById(e).orElseThrow().getConfirmationExits());
    }

    @Autowired UnitEquipmentService unitEquipment;
    @Autowired PlayerItemRepository inventory;
    @Autowired OfficerEquipmentRepository officerGear;
    @Test void carriedOfficerGearSkillsChiefAndUnitEquipmentAreFrozenAndChiefSkillsExcluded() {
        Long a = player(); createBuilding(a, "headquarters", 1);
        var chief = createOfficer(a, "commander_in_chief", 110, 120, 130, 140); chief.setSkills("[{\"id\":\"counter\",\"lv\":5}]"); chief = officerRepository.save(chief);
        var leader = createOfficer(a, "idle", 60, 70, 80, 90); leader.setSkills("[{\"id\":\"frenzy\",\"lv\":1}]"); leader = officerRepository.save(leader);
        OfficerEquipment gear = new OfficerEquipment(); gear.setPlayerId(a); gear.setOfficerId(leader.getId()); gear.setItemKey("test"); gear.setSetType("none"); gear.setTier(1); gear.setSlot("weapon");
        gear.setMilitaryBonus(10); gear.setDefenseBonus(5); gear.setLogisticsBonus(0); gear.setKnowledgeBonus(0); gear.setCreatedAt(1L); gear.setEquippedAt(1L); officerGear.save(gear);
        PlayerItem item = new PlayerItem(); item.setPlayerId(a); item.setItemKey("ue_infantry_helmet"); item.setCount(1); item.setUpdatedAt(1L); inventory.save(item);
        unitEquipment.save(a, "infantry", Map.of("protection", "ue_infantry_helmet"), null);
        var preset = (PvpPreset) pvp.preset(a).get("preset");
        var snap = model.estimate(snapshots.freeze(a, new PvpPreset(0, "LAND_AIR", Map.of("infantry", 100), leader.getId(), preset.defaults())));
        assertEquals(70, snap.military()); assertEquals(75, snap.defense()); assertEquals(Map.of("frenzy", 1), snap.skills()); assertEquals(110, snap.chief().military());
        assertEquals(1.08, UnitEquipmentEffects.mul(snap.tech(), "infantry", "defense"), 1e-9);
        String ticket = (String) pvp.enqueue(a, id(), 0, snap, Map.of("gear", 1)).get("ticketId");
        leader.setMilitary(200); leader.setRole("march"); officerRepository.save(leader); chief.setMilitary(200); chief.setRole("idle"); officerRepository.save(chief); officerGear.delete(gear);
        unitEquipment.save(a, "infantry", Map.of(), null);
        var frozen = PvpJson.read(tickets.findById(ticket).orElseThrow().getSnapshot(), PvpSnapshot.class);
        assertEquals(snap, frozen);
        assertTrue(((List<?>) frozen.officerEquipment()).size() > 0);
    }

    @Test void simultaneousSurrenderHasOneWinnerAndTransactionRollbackHasNoWorldOrPracticeEffects() throws Exception {
        Long a = player(), b = player(); var before = world(a); String session = battle(a, b);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            pvp.surrender(a, id(), session, Map.of("rollback", 1)); throw new IllegalStateException("rollback");
        }));
        assertEquals("BATTLING", pvp.status(a).get("status")); assertEquals(0, states.findById(a).orElseThrow().getGames()); assertTrue(pvp.history(a, 0).isEmpty());
        var pool = Executors.newFixedThreadPool(2); var gate = new CyclicBarrier(2);
        try {
            var first = pool.submit(() -> { gate.await(); return pvp.surrender(a, id(), session, Map.of("first", 1)); });
            var second = pool.submit(() -> { gate.await(); return pvp.surrender(b, id(), session, Map.of("second", 1)); });
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
            assertEquals(Set.of("WIN", "LOSS"), Set.of(pvp.report(a, session).get("result"), pvp.report(b, session).get("result")));
            assertEquals(1, states.findById(a).orElseThrow().getGames()); assertEquals(before, world(a));
        } finally { pool.shutdownNow(); }
    }
}
