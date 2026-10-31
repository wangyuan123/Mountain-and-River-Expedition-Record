package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.service.quest.*;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** 定时入口和并发入口刻意不使用测试外层事务，避免掩盖事务代理问题。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:gameplay_audit_fixes;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameplayAuditFixesTest {
    @Autowired PlatformTransactionManager transactions;
    @Autowired PlayerRepository players;
    @Autowired ResourcesRepository resources;
    @Autowired BuildingRepository buildings;
    @Autowired PlayerCityRepository cities;
    @Autowired PlayerQuestRepository quests;
    @Autowired PlayerQuestStageRepository stages;
    @Autowired CitySurrenderOfferRepository offers;
    @Autowired MarketOrderRepository orders;
    @Autowired WoundedUnitRepository wounded;
    @Autowired OfficerRepository officers;
    @Autowired ConstructionRepository construction;
    @Autowired QuestService questService;
    @Autowired CitySurrenderService surrender;
    @Autowired MarketService market;
    @Autowired WoundedService hospital;
    @Autowired GarrisonFoodService food;
    @Autowired BuildService build;
    @Autowired TickService ticks;
    @Autowired CityScope scope;

    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(s -> work.get()); }
    private Player player() {
        Player p = new Player(); p.setUsername("audit_fix_" + UUID.randomUUID()); p.setPasswordHash("test-only");
        p.setGameInitialized(true); p.setTax(0); p.setMorale(100); p.setLastTick(GameClock.now());
        p.setCivilianPopulation(100); p.setCityPosX(10); p.setCityPosY(10); p = players.save(p);
        Resources r = new Resources(); r.setPlayerId(p.getId()); r.setFood(100_000); r.setSteel(100_000);
        r.setOil(100_000); r.setRare(100_000); r.setGold(100_000); r.setDiamond(1000); resources.save(r); return p;
    }
    private Building building(Long id, int slot, String type, int level) {
        Building b = new Building(); b.setPlayerId(id); b.setCitySlot(slot); b.setType(type); b.setLevel(level); return buildings.save(b);
    }
    private PlayerCity city(Long id, int slot, long at) {
        PlayerCity c = new PlayerCity(); c.setWorldId(1L); c.setOwnerId(id); c.setCitySlot(slot);
        c.setName("regression"); c.setX(10 + slot); c.setY(10); c.setLastTick(at); c.setGarrisonFoodAt(at); return cities.save(c);
    }
    private PlayerQuest completed(Long id, String key) {
        var def = QuestCatalog.findQuest(key);
        PlayerQuest q = quests.findByPlayerIdAndQuestId(id, key).orElseGet(PlayerQuest::new);
        q.setPlayerId(id); q.setQuestId(key); q.setTargetValue(def.targetValue());
        q.setProgress(def.targetValue()); q.setStatus("completed"); return quests.save(q);
    }

    @Test void pendingSurrenderExpiresThroughSchedulerWithoutOuterTransaction() {
        Long id = tx(() -> {
            Player a = player(), b = player(); CitySurrenderOffer o = new CitySurrenderOffer();
            o.setWorldId(1L); o.setInitiatorId(a.getId()); o.setDefenderId(b.getId()); o.setCityId(987654L);
            o.setCityName("expired"); o.setCityX(1); o.setCityY(1); o.setStatus("PENDING");
            o.setSentAt(0L); o.setResponseExpiresAt(GameClock.now() - 1); return offers.save(o).getId();
        });
        surrender.settleDue(); assertEquals("EXPIRED", offers.findById(id).orElseThrow().getStatus());
        surrender.settleDue(); assertEquals("EXPIRED", offers.findById(id).orElseThrow().getStatus());
    }
    @Test void acceptedSurrenderCompletesAndUnfreezesThroughScheduler() {
        Long[] ids = tx(() -> {
            long now = GameClock.now(); Player a = player(), b = player(); a.setMilitaryRank(17);
            PlayerCity c = city(b.getId(), 1, now);
            Resources r = new Resources(); r.setPlayerId(b.getId()); r.setCitySlot(1); resources.save(r);
            CitySurrenderOffer o = new CitySurrenderOffer(); o.setWorldId(1L); o.setInitiatorId(a.getId()); o.setDefenderId(b.getId());
            o.setCityId(c.getId()); o.setCityName(c.getName()); o.setCityX(11); o.setCityY(10); o.setStatus("ACCEPTED");
            o.setSentAt(now - 172_800_000L); o.setResponseExpiresAt(now - 86_400_000L);
            o.setAcceptedAt(now - 86_400_000L); o.setTransferDeadlineAt(now - 1); offers.saveAndFlush(o);
            c.setSurrenderOfferId(o.getId()); c.setSurrenderRecipientId(a.getId());
            c.setSurrenderFrozenAt(now - 86_400_000L); c.setSurrenderTransferDeadlineAt(now - 1);
            return new Long[]{o.getId(), c.getId(), a.getId()};
        });
        surrender.settleDue(); assertEquals("COMPLETED", offers.findById(ids[0]).orElseThrow().getStatus());
        PlayerCity c = cities.findById(ids[1]).orElseThrow(); assertEquals(ids[2], c.getOwnerId()); assertFalse(c.isSurrenderFrozen(GameClock.now()));
    }
    @Test void buildingQuestTargetsNeverExceedTheCurrentCap() {
        QuestCatalog.CHAPTERS.stream().flatMap(c -> c.quests().stream()).filter(q -> "BUILD_UPGRADE_DONE".equals(q.eventType()))
                .forEach(q -> assertTrue(q.targetValue() <= 10, q.id()));
    }
    @Test void failingSurrenderRollsBackOnlyItsOwnTransfer() {
        Long[][] ids = tx(() -> {
            Long[][] rows = new Long[2][];
            for (int i = 0; i < 2; i++) {
                long now = GameClock.now(); Player a = player(), b = player(); a.setMilitaryRank(17);
                PlayerCity c = city(b.getId(), 1, now);
                // 第一城缺少必需的资源行，制造真实移交失败，第二城应继续完成。
                if (i == 1) { Resources r = new Resources(); r.setPlayerId(b.getId()); r.setCitySlot(1); resources.save(r); }
                CitySurrenderOffer o = new CitySurrenderOffer(); o.setWorldId(1L); o.setInitiatorId(a.getId()); o.setDefenderId(b.getId());
                o.setCityId(c.getId()); o.setCityName(c.getName()); o.setCityX(c.getX()); o.setCityY(c.getY()); o.setStatus("ACCEPTED");
                o.setSentAt(now - 172_800_000L); o.setResponseExpiresAt(now - 86_400_000L);
                o.setAcceptedAt(now - 86_400_000L); o.setTransferDeadlineAt(now - 1); offers.saveAndFlush(o);
                c.setSurrenderOfferId(o.getId()); c.setSurrenderRecipientId(a.getId());
                c.setSurrenderFrozenAt(now - 86_400_000L); c.setSurrenderTransferDeadlineAt(now - 1);
                rows[i] = new Long[]{o.getId(), c.getId(), b.getId()};
            }
            return rows;
        });
        surrender.settleDue();
        assertEquals("ACCEPTED", offers.findById(ids[0][0]).orElseThrow().getStatus());
        assertEquals(ids[0][2], cities.findById(ids[0][1]).orElseThrow().getOwnerId());
        assertEquals("COMPLETED", offers.findById(ids[1][0]).orElseThrow().getStatus());
        // 清理故意损坏的邀请，避免影响其他用例的定时结算探测。
        tx(() -> { offers.deleteById(ids[0][0]); return null; });
    }
    @Test void lockedChapterCannotClaimTaskOrStageRewards() {
        tx(() -> {
            Long id = player().getId(); QuestCatalog.findChapter("stage2").quests().forEach(q -> completed(id, q.id()));
            long gold = resources.findByPlayerId(id).orElseThrow().getGold();
            assertEquals(false, questService.claim(id, "q2_1").get("success"));
            assertEquals(false, questService.claimStage(id, "stage2").get("success"));
            assertEquals(gold, resources.findByPlayerId(id).orElseThrow().getGold());
            var view = questService.getPlayerQuests(id).stream().filter(c -> "stage2".equals(c.get("id"))).findFirst().orElseThrow();
            assertEquals("locked", view.get("status")); assertEquals(false, view.get("canClaimStage")); return null;
        });
    }
    @Test void prerequisiteIsCheckedAndClaimIsIdempotent() {
        tx(() -> {
            Long id = player().getId(); building(id, 0, "farm", 2); completed(id, "q1_2");
            assertEquals(false, questService.claim(id, "q1_2").get("success"));
            building(id, 0, "house", 2); completed(id, "q1_1");
            assertEquals(true, questService.claim(id, "q1_2").get("success"));
            assertEquals(false, questService.claim(id, "q1_2").get("success")); return null;
        });
    }
    @Test void historicOutOfOrderClaimCannotUnlockTheFollowingChapter() {
        tx(() -> {
            Long id = player().getId(); questService.getPlayerQuests(id);
            PlayerQuestStage second = stages.findByPlayerIdAndStageId(id, "stage2").orElseThrow(); second.setStatus("claimed");
            var view = questService.getPlayerQuests(id);
            assertEquals("claimed", view.get(1).get("status")); assertEquals("locked", view.get(2).get("status"));
            assertEquals(false, questService.claimStage(id, "stage3").get("success")); return null;
        });
    }
    @Test void separateLevelOneHousesDoNotCompleteALevelTwoQuest() {
        tx(() -> {
            Long id = player().getId();
            for (int slot = 0; slot < 2; slot++) {
                Construction c = new Construction(); c.setPlayerId(id); c.setBuildingType("house"); c.setTargetLevel(1);
                c.setSlot(slot); c.setStartAt(0L); c.setFinishAt(GameClock.now()); construction.save(c); build.completeUpgrade(id, GameClock.now());
            }
            PlayerQuest q = quests.findByPlayerIdAndQuestId(id, "q1_1").orElseThrow();
            assertEquals(1, q.getProgress()); assertEquals("in_progress", q.getStatus()); return null;
        });
    }
    @Test void snapshotRepairsUnclaimedBadProgressAndTargetsButKeepsClaimedRecords() {
        tx(() -> {
            Long id = player().getId(); PlayerQuest wrong = completed(id, "q1_1"); wrong.setTargetValue(20);
            building(id, 0, "house", 1); PlayerQuest paid = completed(id, "q1_2"); paid.setStatus("claimed");
            questService.getPlayerQuests(id); assertEquals(2, wrong.getTargetValue()); assertEquals(1, wrong.getProgress());
            assertEquals("in_progress", wrong.getStatus()); assertNull(wrong.getCompletedAt()); assertEquals("claimed", paid.getStatus());
            assertEquals(false, questService.claim(id, "q1_1").get("success")); return null;
        });
    }
    @Test void existingLateBuildingTargetsAreUpdatedAndSubcityHallCannotSubstituteForMainCity() {
        tx(() -> {
            Long id = player().getId(); PlayerQuest q = completed(id, "q6_5"); q.setTargetValue(15);
            building(id, 1, "command", 10); questService.onEvent(id, "BUILD_UPGRADE_DONE", "command", 1);
            assertEquals(8, q.getTargetValue()); assertEquals(0, q.getProgress()); assertEquals("in_progress", q.getStatus());
            building(id, 0, "command", 8); questService.onEvent(id, "BUILD_UPGRADE_DONE", "command", 1);
            assertEquals(8, q.getProgress()); assertEquals("completed", q.getStatus()); return null;
        });
    }
    @Test void coreMaxSnapshotRequiresMainCityBuildingsAtCurrentCap() {
        tx(() -> {
            Long id = player().getId(); var keys = List.of("command", "house", "farm", "refinery", "oilfield", "raremine", "factory", "airport", "port", "lab", "wall");
            keys.forEach(k -> building(id, 1, k, 10)); questService.onEvent(id, "BUILD_ALL_MAX");
            assertEquals(0, quests.findByPlayerIdAndQuestId(id, "q8_1").orElseThrow().getProgress());
            keys.forEach(k -> building(id, 0, k, 10)); questService.onEvent(id, "BUILD_ALL_MAX");
            assertEquals("completed", quests.findByPlayerIdAndQuestId(id, "q8_1").orElseThrow().getStatus()); return null;
        });
    }
    @Test void inlandMainCityCanCompleteCoreBuildingsWithoutAnUnavailablePort() {
        tx(() -> {
            Long id = player().getId(); city(id, 0, GameClock.now());
            for (String key : List.of("command", "house", "farm", "refinery", "oilfield", "raremine", "factory", "airport", "lab", "wall")) {
                building(id, 0, key, 10);
            }
            questService.onEvent(id, "BUILD_ALL_MAX");
            assertEquals("completed", quests.findByPlayerIdAndQuestId(id, "q8_1").orElseThrow().getStatus()); return null;
        });
    }
    @Test void completedCountedEventsAndPastArmyMilestonesKeepTheirCompletion() {
        tx(() -> {
            Long id = player().getId(); PlayerQuest battle = completed(id, "q3_5"); PlayerQuest army = completed(id, "q1_4");
            questService.onEvent(id, "ARMY_TOTAL"); questService.getPlayerQuests(id);
            assertEquals("completed", army.getStatus()); assertEquals("completed", battle.getStatus());
            assertEquals(100, army.getProgress()); assertEquals(1, battle.getProgress()); return null;
        });
    }
    @Test void crossCityConcurrentOrdersCannotExceedAccountSlots() throws Exception {
        Long id = tx(() -> {
            Long p = player().getId(); building(p, 0, "exchange", 1); building(p, 1, "exchange", 1);
            city(p, 1, GameClock.now());
            Resources r = new Resources(); r.setPlayerId(p); r.setCitySlot(1); r.setFood(100_000); resources.save(r);
            market.createOrder(p, 0, "food", 1, 1); market.createOrder(p, 0, "food", 1, 1); return p;
        });
        CyclicBarrier barrier = new CyclicBarrier(2); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int slot = 0; slot < 2; slot++) { int selected = slot;
                futures.add(pool.submit(() -> {
                    try { return tx(() -> {
                        resources.findByPlayerIdAndCitySlot(id, selected).orElseThrow();
                        try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
                        market.createOrder(id, selected, "food", 1, 1); return true;
                    }); } catch (IllegalArgumentException e) {
                        assertTrue(e.getMessage().contains("槽位")); return false;
                    }
                }));
            }
            int successes = 0; for (var f : futures) if (f.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes); assertEquals(3, orders.countBySellerIdAndStatus(id, "ACTIVE"));
            assertEquals(199_997, resources.findByPlayerIdAndCitySlot(id, 0).orElseThrow().getFood()
                    + resources.findByPlayerIdAndCitySlot(id, 1).orElseThrow().getFood());
        } finally { pool.shutdownNow(); }
    }
    @Test void healedTroopsOnlyConsumeFoodAfterReturningForEachCityAndCurrency() {
        for (boolean managed : List.of(true, false)) for (int slot : List.of(0, 1)) for (String currency : List.of("gold", "diamond")) tx(() -> {
            long now = GameClock.now(); Long id = player().getId(); PlayerCity c = city(id, slot, now - 3_600_000L);
            players.findById(id).orElseThrow().setLastTick(now - 3_600_000L);
            if (!managed) c.setGarrisonFoodAt(null);
            if (slot == 1) { Resources r = new Resources(); r.setPlayerId(id); r.setCitySlot(1); r.setGold(100_000); resources.save(r); }
            Resources r = resources.findByPlayerIdAndCitySlot(id, slot).orElseThrow(); r.setFood(10_000);
            WoundedUnit w = new WoundedUnit(); w.setPlayerId(id); w.setCitySlot(slot); w.setCityId(c.getId());
            w.setCityName(c.getName()); w.setCityX(c.getX()); w.setCityY(c.getY());
            w.setType("infantry"); w.setCount(100); w.setCreatedAt(now - 3_600_000L); w.setExpiresAt(now + 3_600_000L); w.setRecoveryPercent(50); wounded.save(w);
            try (var selected = scope.enter(id, slot); var clock = GameClock.use(() -> now)) {
                hospital.heal(id, w.getId(), 50, currency); hospital.heal(id, w.getId(), 50, currency);
                food.settle(c, now); assertEquals(10_000, r.getFood()); food.settle(c, now + 3_600_000L); assertEquals(9_900, r.getFood());
            } catch (Exception e) { throw new RuntimeException(e); } return null;
        });
    }
    private long settledGold(long opening, int salary, int steps) {
        long from = GameClock.now(); Long id = tx(() -> {
            // 民心70、税率30、人口恰好满额，隔离人口逃亡对税基的影响，只验证收支结算。
            Player p = player(); p.setTax(30); p.setMorale(70); p.setCivilianPopulation(6000); p.setLastTick(from);
            building(p.getId(), 0, "house", 5); resources.findByPlayerId(p.getId()).orElseThrow().setGold(opening);
            Officer o = new Officer(); o.setPlayerId(p.getId()); o.setName("salary"); o.setRole("idle"); o.setSalary(salary); o.setLoyalty(80); officers.save(o); return p.getId();
        });
        for (int i = 1; i <= steps; i++) { long at = from + i * 3_600_000L / steps;
            try (var clock = GameClock.use(() -> at)) { ticks.tick(id); } catch (Exception e) { throw new RuntimeException(e); }
        }
        return resources.findByPlayerId(id).orElseThrow().getGold();
    }
    @Test void taxAndSalaryAtCapDoNotDependOnSettlementFrequency() {
        assertEquals(20_000_000L, settledGold(20_000_000L, 100, 1)); assertEquals(20_000_000L, settledGold(20_000_000L, 100, 12));
        assertEquals(settledGold(19_999_950L, 100, 1), settledGold(19_999_950L, 100, 12));
    }
    @Test void aboveCapStockPaysSalaryBeforeTaxResumes() {
        assertEquals(20_000_000L, settledGold(20_000_050L, 100, 1)); assertEquals(20_000_000L, settledGold(20_000_050L, 100, 12));
        assertEquals(20_000_900L, settledGold(20_001_000L, 100, 1)); assertEquals(20_000_900L, settledGold(20_001_000L, 100, 12));
        assertEquals(20_001_000L, settledGold(20_001_000L, 0, 12));
        assertEquals(settledGold(20_000_000L, 7000, 1), settledGold(20_000_000L, 7000, 12));
    }
}
