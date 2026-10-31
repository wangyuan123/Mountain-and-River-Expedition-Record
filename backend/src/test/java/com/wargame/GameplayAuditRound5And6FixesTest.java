package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.service.quest.QuestService;
import com.wargame.util.GameClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立 H2 合成账号，验证第五、六轮的正常经济、完工通知和归降资产保留契约。 */
class GameplayAuditRound5And6FixesTest extends BaseServiceTest {
    private static final long START = 1_780_000_000_000L;
    private static final long HOUR = 3_600_000L;
    @Autowired CityScope scope;
    @Autowired CityCapacityService capacity;
    @Autowired CityFreezeService freeze;
    @Autowired CitySurrenderService surrender;
    @Autowired CityConquestService conquest;
    @Autowired CityService cityService;
    @Autowired CitySurrenderOfferRepository offers;
    @Autowired GarrisonFoodService food;
    @Autowired ArmyService army;
    @Autowired ArmyProductionQueueRepository production;
    @Autowired TechResearchQueueRepository research;
    @Autowired DepotService depot;
    @Autowired PlayerItemRepository items;
    @Autowired QuestService quests;
    @Autowired MarketService market;
    @Autowired WoundedService woundedService;
    @Autowired WoundedUnitRepository wounded;
    @Autowired ResourceOverflowService overflow;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockBean WebSocketPushService pushes;

    private Player player(String name) {
        Player p = createTestPlayer("synthetic-r56-" + name, 0);
        p.setLastTick(START); p.setCivilianPopulation(0); p.setMilitaryRank(2);
        return playerRepository.save(p);
    }

    private PlayerCity city(Player p, int slot) {
        PlayerCity c = new PlayerCity(); c.setOwnerId(p.getId()); c.setCitySlot(slot); c.setWorldId(1L);
        c.setName("隔离测试城" + slot); c.setX(10 + p.getId().intValue() * 3); c.setY(10 + slot * 3);
        c.setReadyAt(0L); c.setLastTick(START); c.setCivilianPopulation(0);
        c = playerCityRepository.saveAndFlush(c);
        if (slot > 0) {
            Resources r = new Resources(); r.setPlayerId(p.getId()); r.setCitySlot(slot);
            r.setFood(10_000); r.setGold(0); r.setSteel(0); r.setOil(0); r.setRare(0);
            resourcesRepository.save(r);
        }
        return c;
    }

    private CitySurrenderOffer accepted(Player recipient, Player donor, PlayerCity c) {
        CitySurrenderOffer o = new CitySurrenderOffer(); o.setWorldId(c.getWorldId()); o.setCityId(c.getId());
        o.setCityName(c.getName()); o.setCityX(c.getX()); o.setCityY(c.getY());
        o.setInitiatorId(recipient.getId()); o.setDefenderId(donor.getId()); o.setStatus("ACCEPTED");
        o.setSentAt(START - 1000); o.setResponseExpiresAt(START + 24 * HOUR);
        o.setAcceptedAt(START); o.setTransferDeadlineAt(START + 24 * HOUR); o = offers.saveAndFlush(o);
        c.setSurrenderOfferId(o.getId()); c.setSurrenderRecipientId(recipient.getId());
        c.setSurrenderFrozenAt(START); c.setSurrenderTransferDeadlineAt(o.getTransferDeadlineAt());
        playerCityRepository.saveAndFlush(c);
        return o;
    }

    private WoundedUnit batch(PlayerCity c, long deadline) {
        WoundedUnit w = new WoundedUnit(); w.setPlayerId(c.getOwnerId()); w.setCitySlot(c.getCitySlot());
        w.setCityId(c.getId()); w.setCityX(c.getX()); w.setCityY(c.getY()); w.setCityName(c.getName());
        w.setType("infantry"); w.setCount(10); w.setCreatedAt(START - HOUR); w.setExpiresAt(deadline);
        w.setRecoveryPercent(50); return wounded.saveAndFlush(w);
    }

    private ArmyProductionQueue queue(Long owner, int slot, long finish) {
        ArmyProductionQueue q = new ArmyProductionQueue(); q.setPlayerId(owner); q.setCitySlot(slot);
        q.setUnitType("infantry"); q.setUnitCount(1); q.setStartedAt(START); q.setFinishesAt(finish);
        q.setDurationSeconds((int) ((finish - START) / 1000)); q.setSpeedMultiplier(1.0); q.setCostPopulation(1);
        return production.saveAndFlush(q);
    }

    private TechResearchQueue research(Long owner, int slot, long finish) {
        TechResearchQueue q = new TechResearchQueue(); q.setPlayerId(owner); q.setCitySlot(slot);
        q.setTechType("prod_steel"); q.setTargetLevel(1); q.setStartedAt(START); q.setFinishesAt(finish);
        q.setDurationSeconds((int) ((finish - START) / 1000)); return research.saveAndFlush(q);
    }

    @Test void resourceGiftAndCheckinStartPayingWagesOnlyAfterReceipt() throws Exception {
        Player p = player("gift"), checked = player("checkin");
        for (Player owner : List.of(p, checked)) {
            getResources(owner.getId()).setGold(0);
            Officer o = createOfficer(owner.getId(), "idle", 30, 30, 30); o.setSalary(100);
        }
        items.saveAndFlush(new PlayerItem(null, p.getId(), "goldBox", 1, START));
        try (var clock = GameClock.use(() -> START + HOUR)) {
            assertEquals(true, depot.useItem(p.getId(), "goldBox", null, null).get("success"));
            assertEquals(true, quests.claimDailyCheckin(checked.getId()).get("success"));
            tickService.beforeResourceChange(p.getId(), 0);
            assertEquals(50_000, getResources(p.getId()).getGold());
            assertEquals(10_000, getResources(checked.getId()).getGold());
            for (Player owner : List.of(p, checked))
                assertEquals(0, officerRepository.findByPlayerIdAndCitySlot(owner.getId(), 0).get(0).getLoyalty());
        }
    }

    @Test void marketSettlesBothCityLedgersBeforeMovingGold() throws Exception {
        Player seller = player("seller"), buyer = player("buyer");
        createBuilding(seller.getId(), "exchange", 1); createBuilding(buyer.getId(), "exchange", 1);
        getResources(seller.getId()).setGold(0); getResources(buyer.getId()).setGold(10_000);
        Officer o = createOfficer(seller.getId(), "idle", 30, 30, 30); o.setSalary(100);
        MarketOrder order;
        try (var clock = GameClock.use(() -> START)) { order = market.createOrder(seller.getId(), 0, "steel", 100, 10); }
        try (var clock = GameClock.use(() -> START + HOUR)) { market.buyOrder(buyer.getId(), 0, order.getId()); }
        assertEquals(900, getResources(seller.getId()).getGold());
        assertEquals(9_000, getResources(buyer.getId()).getGold());
        assertEquals(0, officerRepository.findById(o.getId()).orElseThrow().getLoyalty());
    }

    @Test void reservedCitySlotIsSharedByFoundingConquestAndTransfer() throws Exception {
        createTestWorld();
        Player recipient = player("reserve"), donor = player("reserve-donor");
        city(recipient, 0); city(donor, 0); PlayerCity incoming = city(donor, 1);
        CitySurrenderOffer offer = accepted(recipient, donor, incoming);
        try (var clock = GameClock.use(() -> START)) {
            assertFalse(capacity.hasRoom(recipient.getId(), null));
            assertTrue(capacity.hasRoom(recipient.getId(), offer.getId()));
            incoming.setMorale(0);
            assertTrue(conquest.unavailableReason(recipient.getId(), incoming).contains("上限"));
            var error = assertThrows(IllegalArgumentException.class,
                    () -> cityService.foundAt(recipient.getId(), null, 80, 80, "合成预留城"));
            assertTrue(error.getMessage().contains("上限"));
        }
        try (var clock = GameClock.use(() -> START + 24 * HOUR)) { surrender.complete(offer.getId(), GameClock.now()); }
        assertEquals("COMPLETED", offers.findById(offer.getId()).orElseThrow().getStatus());
        assertEquals(2, playerCityRepository.findByOwnerId(recipient.getId()).size());
    }

    @Test void tickPublishesTimelineCompletionsAndSharedTechInBothCities() throws Exception {
        Player p = player("notifications"); city(p, 0); city(p, 1);
        createConstruction(p.getId(), "refinery", 1, START, START + 1000, 0).setCostSnapshot("{}");
        research(p.getId(), 1, START + 1000);
        try (var clock = GameClock.use(() -> START + 2000)) { tickService.tick(p.getId()); }
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(pushes, atLeast(2)).pushTickUpdate(eq(p.getId()), data.capture());
        assertTrue(data.getAllValues().stream().anyMatch(m -> ((List<?>) m.getOrDefault("completedBuilds", List.of())).contains("refinery")));
        String techName = com.wargame.model.constants.GameData.TECHS.get("prod_steel").name();
        assertTrue(data.getAllValues().stream().anyMatch(m -> ((List<?>) m.getOrDefault("completedResearch", List.of())).stream()
                .anyMatch(message -> message.toString().contains(techName))));
        assertEquals(2, data.getAllValues().stream().filter(m -> ((Map<?, ?>) m.get("tech")).get("prod_steel").equals(1)).count());
        clearInvocations(pushes);
        try (var clock = GameClock.use(() -> START + 2000)) { tickService.tick(p.getId()); }
        verify(pushes, atLeast(2)).pushTickUpdate(eq(p.getId()), data.capture());
        assertTrue(data.getAllValues().subList(2, 4).stream().allMatch(m -> !m.containsKey("completedBuilds") && !m.containsKey("completedResearch")));
    }

    @Test void populationAndTaxFractionsSurvivePersistenceAcrossShortIntervals() throws Exception {
        Player once = player("population-once"), split = player("population-split");
        for (Player p : List.of(once, split)) { p.setTax(30); createBuilding(p.getId(), "house", 5); getResources(p.getId()).setGold(0); }
        for (int minute = 1; minute <= 60; minute++) {
            long at = START + minute * 60_000L;
            try (var clock = GameClock.use(() -> at)) { tickService.beforeResourceChange(split.getId(), 0); }
            em.flush(); em.clear();
        }
        try (var clock = GameClock.use(() -> START + HOUR)) { tickService.beforeResourceChange(once.getId(), 0); }
        for (Player p : List.of(once, split)) {
            Player actual = playerRepository.findById(p.getId()).orElseThrow();
            assertEquals(180, actual.getCivilianPopulation());
            assertEquals(54, getResources(p.getId()).getGold());
        }
    }

    @Test void ordinaryAndActivatedFoodAccountsShareTheEightHourWindow() throws Exception {
        Player ordinary = player("food-ordinary"), managed = player("food-managed");
        city(ordinary, 0); PlayerCity c = city(managed, 0); c.setGarrisonFoodAt(START);
        for (Player p : List.of(ordinary, managed)) { createArmyUnit(p.getId(), "infantry", 100); getResources(p.getId()).setFood(10_000); }
        try (var clock = GameClock.use(() -> START + 24 * HOUR)) {
            tickService.beforeResourceChange(ordinary.getId(), 0); tickService.beforeResourceChange(managed.getId(), 0);
        }
        assertEquals(9_200, getResources(ordinary.getId()).getFood());
        assertEquals(9_200, getResources(managed.getId()).getFood());
    }

    @ParameterizedTest @CsvSource({"0,false", "0,true", "1,false", "1,true"})
    void returningPopulationPreservesExistingOverCapacityResidents(int slot, boolean cancel) throws Exception {
        Player p = player("return-" + slot + "-" + cancel); PlayerCity c = city(p, slot);
        try (var selected = scope.enter(c); var clock = GameClock.use(() -> START)) {
            CityEconomy local = scope.economy(p.getId()); local.setCivilianPopulation(1500); local.setPopulationGrowthRemainder(.25);
            Building b = new Building(); b.setPlayerId(p.getId()); b.setCitySlot(slot); b.setType("house"); b.setLevel(1); buildingRepository.save(b);
            if (cancel) assertTrue(army.cancelProduction(p.getId(), queue(p.getId(), slot, START + HOUR).getId()));
            else {
                ArmyUnit u = new ArmyUnit(null, p.getId(), "infantry", 1); u.setCitySlot(slot); armyUnitRepository.saveAndFlush(u);
                assertEquals(true, army.dismiss(p.getId(), "infantry", 1).get("success"));
            }
            assertEquals(1500, scope.economy(p.getId()).getCivilianPopulation());
            assertEquals(.25, scope.economy(p.getId()).getPopulationGrowthRemainder(), 1e-9);
        }
    }

    @Test void constructionPrestigeUsesThePaidCostSnapshot() throws Exception {
        Player p = player("prestige"); createBuilding(p.getId(), "house", 1);
        createConstruction(p.getId(), "house", 2, START, START + 1000, 0).setCostSnapshot("{\"steel\":911,\"food\":455}");
        createTechnology(p.getId(), "log_build", 10);
        try (var clock = GameClock.use(() -> START + 1000)) { buildService.completeUpgrade(p.getId(), GameClock.now()); }
        assertEquals(13, playerRepository.findById(p.getId()).orElseThrow().getPrestige());
    }

    @Test void starUpKeepsTheLoyaltySettledBeforeItemConsumption() throws Exception {
        Player p = player("star"); getResources(p.getId()).setGold(0);
        Officer o = createOfficer(p.getId(), "idle", 30, 30, 30); o.setSalary(100);
        items.saveAndFlush(new PlayerItem(null, p.getId(), "starUp", 1, START));
        try (var clock = GameClock.use(() -> START + HOUR)) { assertEquals(true, depot.useItem(p.getId(), "starUp", o.getId(), null).get("success")); }
        assertEquals(0, officerRepository.findById(o.getId()).orElseThrow().getLoyalty());
        assertEquals(0, items.findByPlayerIdAndItemKey(p.getId(), "starUp").orElseThrow().getCount());
    }

    @Test void serverRatesMatchNetGoldAtCapacityAndDoNotTreatNewbieProtectionAsAnEconomicBuff() throws Exception {
        Player p = player("rates"); p.setCivilianPopulation(1000); p.setTax(30); p.setNewbieProtectionStartedAt(START);
        createBuilding(p.getId(), "house", 1); createBuilding(p.getId(), "refinery", 1);
        Officer o = createOfficer(p.getId(), "idle", 30, 30, 30); o.setSalary(100); getResources(p.getId()).setGold(20_000_000);
        try (var clock = GameClock.use(() -> START)) {
            Map<?, ?> rates = tickService.resourceRates(p.getId());
            assertEquals(0.0, ((Map<?, ?>) rates.get("netPerHour")).get("gold"));
            assertEquals(tickService.produceOf(p.getId(), "refinery"), ((Map<?, ?>) rates.get("productionPerHour")).get("steel"));
        }
        try (var clock = GameClock.use(() -> START + HOUR)) { tickService.beforeResourceChange(p.getId(), 0); }
        assertEquals(20_000_000, getResources(p.getId()).getGold());
    }

    @Test void frozenCitySkipsFoodCallbacksAndOverflowWithoutAdvancingInspectionStage() throws Exception {
        Player recipient = player("freeze-recipient"), donor = player("freeze-donor");
        PlayerCity c = city(donor, 1); accepted(recipient, donor, c); c.setGarrisonFoodAt(START);
        ArmyUnit unit = new ArmyUnit(null, donor.getId(), "infantry", 100); unit.setCitySlot(1); armyUnitRepository.save(unit);
        Resources stock = resourcesRepository.findByPlayerIdAndCitySlot(donor.getId(), 1).orElseThrow();
        stock.setFood(ResourceOverflowService.STOCK_LIMIT + 100);
        jdbc.execute("CREATE TABLE IF NOT EXISTS game_schedule(task_key VARCHAR(64) PRIMARY KEY,last_period BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS resource_overflow_state(resource_id BIGINT,resource_type VARCHAR(20),stage INT,last_period BIGINT,PRIMARY KEY(resource_id,resource_type))");
        jdbc.update("DELETE FROM game_schedule WHERE task_key='resource-overflow'");
        try (var clock = GameClock.use(() -> START + HOUR)) {
            food.beforeGlobalChange(donor.getId(), GameClock.now()); overflow.checkPeriod(1); overflow.checkPeriod(2);
            assertEquals(ResourceOverflowService.STOCK_LIMIT + 100, stock.getFood());
            assertEquals(START, c.getGarrisonFoodAt());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_overflow_state WHERE resource_id=?", Integer.class, stock.getId()));
            freeze.resume(c, GameClock.now()); overflow.checkPeriod(3);
            assertEquals(ResourceOverflowService.STOCK_LIMIT + 50, stock.getFood());
        }
    }

    @Test void peacefulTransferPreservesFrozenFoodAndRemainingWoundedTime() throws Exception {
        Player recipient = player("transfer-recipient"), donor = player("transfer-donor");
        city(recipient, 0); city(donor, 0); PlayerCity c = city(donor, 1);
        c.setGarrisonFoodAt(START); CitySurrenderOffer offer = accepted(recipient, donor, c);
        ArmyUnit unit = new ArmyUnit(null, donor.getId(), "infantry", 100); unit.setCitySlot(1); armyUnitRepository.save(unit);
        WoundedUnit w = batch(c, START + 2 * HOUR);
        WoundedUnit expiredBeforeFreeze = batch(c, START);
        try (var clock = GameClock.use(() -> START + 24 * HOUR)) {
            woundedService.expireWounded(); assertTrue(wounded.existsById(w.getId()));
            assertFalse(wounded.existsById(expiredBeforeFreeze.getId()));
            surrender.complete(offer.getId(), GameClock.now());
            try (var selected = scope.enter(recipient.getId(), 1)) { tickService.beforeResourceChange(recipient.getId(), 1); }
        }
        assertEquals(recipient.getId(), c.getOwnerId()); assertEquals(START + 24 * HOUR, c.getGarrisonFoodAt());
        assertEquals(10_000, resourcesRepository.findByPlayerIdAndCitySlot(recipient.getId(), 1).orElseThrow().getFood());
        assertEquals(START + 26 * HOUR, wounded.findById(w.getId()).orElseThrow().getExpiresAt());
        try (var clock = GameClock.use(() -> START + 25 * HOUR)) { tickService.beforeResourceChange(recipient.getId(), 1); }
        assertEquals(9_900, resourcesRepository.findByPlayerIdAndCitySlot(recipient.getId(), 1).orElseThrow().getFood());
    }

    @Test void systemCancellationRestoresEveryPausedClockExactlyOnce() throws Exception {
        Player recipient = player("cancel-recipient"), donor = player("cancel-donor");
        city(recipient, 0); city(donor, 0); PlayerCity c = city(donor, 1); c.setGarrisonFoodAt(START);
        CitySurrenderOffer offer = accepted(recipient, donor, c);
        Construction b = new Construction(); b.setPlayerId(donor.getId()); b.setCitySlot(1); b.setBuildingType("house");
        b.setTargetLevel(1); b.setSlot(0); b.setStartAt(START); b.setFinishAt(START + 2 * HOUR); constructionRepository.saveAndFlush(b);
        ArmyProductionQueue a = queue(donor.getId(), 1, START + 3 * HOUR);
        TechResearchQueue t = research(donor.getId(), 1, START + 4 * HOUR); WoundedUnit w = batch(c, START + HOUR);
        recipient.setDisabled(1); playerRepository.saveAndFlush(recipient);
        try (var clock = GameClock.use(() -> START + 24 * HOUR)) {
            surrender.complete(offer.getId(), GameClock.now());
            freeze.resume(c, GameClock.now()); surrender.complete(offer.getId(), GameClock.now());
            assertEquals("CANCELLED", offers.findById(offer.getId()).orElseThrow().getStatus());
            assertFalse(c.isSurrenderFrozen(GameClock.now()));
        }
        assertEquals(START + 26 * HOUR, b.getFinishAt()); assertEquals(START + 27 * HOUR, a.getFinishesAt());
        assertEquals(START + 28 * HOUR, t.getFinishesAt()); assertEquals(START + 25 * HOUR, w.getExpiresAt());
        assertEquals(START + 24 * HOUR, c.getGarrisonFoodAt()); assertEquals(START + 24 * HOUR, c.getLastTick());
    }

    @Test void vipHealingPricesAndRecoveryUseThePublishedRules() {
        Player p = player("healing"); p.setVipLevel(5);
        assertEquals(35, woundedService.goldCost(p.getId(), "infantry", 10));
        p.setVipLevel(8); assertEquals(660, woundedService.goldCost(p.getId(), "infantry", 220));
        assertEquals(2, woundedService.diamondCost("infantry", 220));
        createTechnology(p.getId(), "log_medical", 10);
        Officer o = createOfficer(p.getId(), "commander", 30, 30, 30); o.setSkills("[{\"id\":\"medic\",\"lv\":5}]");
        assertEquals(65, woundedService.recoveryPercent(p.getId(), o));
    }

    @Test void acceptingAnOfferSettlesOldEconomyBeforeStartingTheFreeze() throws Exception {
        createTestWorld();
        Player recipient = player("accept-recipient"), donor = player("accept-donor");
        city(recipient, 0); city(donor, 0); PlayerCity c = city(donor, 1);
        ArmyUnit u = new ArmyUnit(null, donor.getId(), "infantry", 100); u.setCitySlot(1); armyUnitRepository.save(u);
        Long offerId;
        try (var clock = GameClock.use(() -> START)) { offerId = (Long) surrender.offer(recipient.getId(), c.getId()).get("offerId"); }
        try (var clock = GameClock.use(() -> START + HOUR)) {
            assertEquals("ACCEPTED", surrender.respond(donor.getId(), offerId, "accept").get("status"));
        }
        assertEquals(9_900, resourcesRepository.findByPlayerIdAndCitySlot(donor.getId(), 1).orElseThrow().getFood());
        assertEquals(START + HOUR, c.getSurrenderFrozenAt());
        assertEquals(START + HOUR, c.getLastTick());
    }

    @Test void failedReservationRecheckRestoresTheOriginalCity() throws Exception {
        Player recipient = player("capacity-recipient"), donor = player("capacity-donor");
        city(recipient, 0); city(donor, 0); PlayerCity c = city(donor, 1);
        CitySurrenderOffer offer = accepted(recipient, donor, c);
        recipient.setMilitaryRank(1);
        try (var clock = GameClock.use(() -> START + 24 * HOUR)) { surrender.complete(offer.getId(), GameClock.now()); }
        assertEquals("CANCELLED", offers.findById(offer.getId()).orElseThrow().getStatus());
        assertEquals(donor.getId(), c.getOwnerId()); assertNull(c.getSurrenderFrozenAt());
        assertEquals(START + 24 * HOUR, c.getLastTick());
    }

    @Test void resumingAlsoPausesTheSalaryGraceClockWithoutChangingRecruitmentFacts() throws Exception {
        Player recipient = player("salary-recipient"), donor = player("salary-donor");
        PlayerCity c = city(donor, 1); accepted(recipient, donor, c);
        Officer o = createOfficer(donor.getId(), "idle", 30, 30, 30); o.setCitySlot(1); o.setSalary(100);
        o.setRecruitAt(START - HOUR);
        Resources stock = resourcesRepository.findByPlayerIdAndCitySlot(donor.getId(), 1).orElseThrow();
        stock.setGold(0); stock.setSalaryArrearsStartedAt(START - 30_000);
        try (var clock = GameClock.use(() -> START + 24 * HOUR)) { freeze.resume(c, GameClock.now()); }
        assertEquals(START - HOUR, o.getRecruitAt());
        assertEquals(START + 24 * HOUR - 30_000, stock.getSalaryArrearsStartedAt());
        try (var clock = GameClock.use(() -> START + 24 * HOUR + 30_000)) { tickService.beforeResourceChange(donor.getId(), 1); }
        assertEquals(78, officerRepository.findById(o.getId()).orElseThrow().getLoyalty());
    }
}
