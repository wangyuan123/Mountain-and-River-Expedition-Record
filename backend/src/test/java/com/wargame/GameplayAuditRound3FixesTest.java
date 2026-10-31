package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实服务回归覆盖资产守恒、经济变更边界和生产通道，使用隔离测试数据库。 */
class GameplayAuditRound3FixesTest extends BaseServiceTest {
    @Autowired MailService mail;
    @Autowired OfficerService officers;
    @Autowired PrisonerService prisoners;
    @Autowired FortService forts;
    @Autowired ArmyService army;
    @Autowired TechService tech;
    @Autowired GarrisonFoodService food;
    @Autowired CityScope scope;
    @Autowired AcademyRepository academies;
    @Autowired PrisonerUnitRepository captiveRows;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired MailRepository mails;
    @Autowired PlayerItemRepository items;
    @Autowired DepotService depot;

    @Override protected Resources getResources(Long player) {
        return resourcesRepository.findByPlayerIdAndCitySlot(player, 0).orElseThrow();
    }

    private Player player(long at) {
        Player p = createTestPlayer("round3_" + UUID.randomUUID(), 0);
        p.setGameInitialized(true); p.setLastTick(at); p.setMorale(100); p.setCivilianPopulation(1000);
        giveResources(p.getId(), 10000, 1000000, 1000000, 1000000, 100000);
        getResources(p.getId()).setDiamond(0); return p;
    }
    private PlayerCity city(Long owner, int slot, long at, boolean managed) {
        PlayerCity c = new PlayerCity(); c.setWorldId(1L); c.setOwnerId(owner); c.setCitySlot(slot);
        c.setName("regression"); c.setX(10 + slot); c.setY(10); c.setLastTick(at); c.setTax(0);
        if (managed) c.setGarrisonFoodAt(at);
        return playerCityRepository.save(c);
    }
    private void candidate(Long owner) {
        Academy a = new Academy(); a.setPlayerId(owner); a.setOfficers(JsonUtil.toJson(List.of(
                Map.of("name", "新军官", "star", 1, "salary", 100, "loyalty", 80)))); academies.save(a);
    }
    private Long recruit(Long owner) {
        var response = officers.recruit(owner, 0); assertEquals(true, response.get("success"), response.toString());
        return ((Number) response.get("officerId")).longValue();
    }
    private Long order(Long owner, int count) {
        var response = army.recruit(owner, "infantry", count);
        assertEquals(true, response.get("success"), response.toString());
        return ((Number) response.get("queueId")).longValue();
    }

    @Test void mailOverflowIsRejectedBeforeAnyDiamondOrMailMutation() {
        long now = GameClock.now(); Player a = player(now), b = player(now);
        assertThrows(IllegalArgumentException.class, () -> mail.send(a.getId(), b.getUsername(), "test", "", List.of(
                Map.of("type", "diamond", "qty", 1000000000), Map.of("type", "diamond", "qty", 1000000000),
                Map.of("type", "diamond", "qty", 1000000000))));
        assertEquals(0, getResources(a.getId()).getDiamond());
        assertTrue(mail.listOutbox(a.getId()).isEmpty());
    }
    @Test void mailResourceTotalsUseLongAndPreserveTransferAmounts() {
        Player a = player(GameClock.now()), b = player(GameClock.now());
        getResources(a.getId()).setSteel(4000000000L);
        var response = mail.send(a.getId(), b.getUsername(), "test", "", List.of(
                Map.of("type", "steel", "qty", 1500000000), Map.of("type", "steel", "qty", 1500000000)));
        assertEquals(1000000000L, getResources(a.getId()).getSteel());
        Long id = ((Number) ((Map<?, ?>) response.get("mail")).get("id")).longValue();
        assertEquals(true, mail.claimAttach(b.getId(), id).get("success"));
        assertEquals(3001000000L, getResources(b.getId()).getSteel());
    }
    @Test void claimRejectsDiamondBalanceOverflowBeforeMixedRewards() {
        Player p = player(GameClock.now()); getResources(p.getId()).setDiamond(Integer.MAX_VALUE - 5);
        Mail m = mail.sendSystem(p.getId(), "系统", "system", "test", "", List.of(
                Map.of("type", "gold", "qty", 100), Map.of("type", "diamond", "qty", 6)));
        assertThrows(IllegalArgumentException.class, () -> mail.claimAttach(p.getId(), m.getId()));
        assertEquals(100000, getResources(p.getId()).getGold());
        assertFalse(mails.findById(m.getId()).orElseThrow().getIsClaimed());
    }
    @Test void mailUsesTheSharedWalletAndSelectedCityResources() throws Exception {
        long now = GameClock.now();
        Player a = player(now), b = player(now);
        city(a.getId(), 1, now, false); city(b.getId(), 1, now, false);
        Resources aCity = new Resources(); aCity.setPlayerId(a.getId()); aCity.setCitySlot(1);
        aCity.setSteel(500); aCity.setDiamond(77); resourcesRepository.save(aCity);
        Resources bCity = new Resources(); bCity.setPlayerId(b.getId()); bCity.setCitySlot(1);
        bCity.setSteel(10); bCity.setDiamond(88); resourcesRepository.save(bCity);
        getResources(a.getId()).setDiamond(100);
        Long id;
        try (var ignored = scope.enter(a.getId(), 1)) {
            var response = mail.send(a.getId(), b.getUsername(), "test", "", List.of(
                    Map.of("type", "steel", "qty", 200), Map.of("type", "diamond", "qty", 40)));
            id = ((Number) ((Map<?, ?>) response.get("mail")).get("id")).longValue();
        }
        assertEquals(300, aCity.getSteel()); assertEquals(77, aCity.getDiamond());
        assertEquals(60, getResources(a.getId()).getDiamond());
        try (var ignored = scope.enter(b.getId(), 1)) { assertEquals(true, mail.claimAttach(b.getId(), id).get("success")); }
        assertEquals(210, bCity.getSteel()); assertEquals(88, bCity.getDiamond());
        assertEquals(40, getResources(b.getId()).getDiamond());
        assertEquals(1000000, getResources(b.getId()).getSteel());
        assertThrows(IllegalArgumentException.class, () -> mail.claimAttach(b.getId(), id));
        assertEquals(40, getResources(b.getId()).getDiamond());
    }
    @Test void mailRejectsLongBalanceOverflowAndInvalidQuantitiesBeforeGranting() {
        for (Object invalid : List.of(0, -1, 1.5, 4294967297L)) {
            Player p = player(GameClock.now());
            Mail m = mail.sendSystem(p.getId(), "系统", "system", "test", "", List.of(
                    Map.of("type", "gold", "qty", 100), Map.of("type", "steel", "qty", invalid)));
            assertThrows(IllegalArgumentException.class, () -> mail.claimAttach(p.getId(), m.getId()));
            assertEquals(100000, getResources(p.getId()).getGold());
            assertFalse(mails.findById(m.getId()).orElseThrow().getIsClaimed());
        }
        Player p = player(GameClock.now()); getResources(p.getId()).setSteel(Long.MAX_VALUE - 4);
        Mail m = mail.sendSystem(p.getId(), "系统", "system", "test", "", List.of(
                Map.of("type", "gold", "qty", 100), Map.of("type", "steel", "qty", 5)));
        assertThrows(IllegalArgumentException.class, () -> mail.claimAttach(p.getId(), m.getId()));
        assertEquals(Long.MAX_VALUE - 4, getResources(p.getId()).getSteel());
        assertEquals(100000, getResources(p.getId()).getGold());
        assertFalse(mails.findById(m.getId()).orElseThrow().getIsClaimed());
    }
    @Test void mailChecksArmyAndItemCapacityBeforeMixedRewards() {
        for (boolean item : List.of(false, true)) {
            Player p = player(GameClock.now()); createBuilding(p.getId(), "factory", 1);
            Map<String, Object> attachment;
            if (item) {
                PlayerItem row = new PlayerItem(); row.setPlayerId(p.getId()); row.setItemKey("commanderRenameCard");
                row.setCount(Integer.MAX_VALUE); row.setUpdatedAt(GameClock.now()); items.save(row);
                attachment = Map.of("type", "item", "itemId", "commanderRenameCard", "qty", 1);
            } else {
                createArmyUnit(p.getId(), "infantry", Integer.MAX_VALUE);
                attachment = Map.of("type", "infantry", "qty", 1);
            }
            Mail m = mail.sendSystem(p.getId(), "系统", "system", "test", "",
                    List.of(Map.of("type", "gold", "qty", 100), attachment));
            assertThrows(IllegalArgumentException.class, () -> mail.claimAttach(p.getId(), m.getId()));
            assertEquals(100000, getResources(p.getId()).getGold());
            assertFalse(mails.findById(m.getId()).orElseThrow().getIsClaimed());
        }
    }
    @Test void newOfficerNeverPaysPreRecruitSalaryOrLosesLoyaltyImmediately() throws Exception {
        long now = GameClock.now();
        try (var clock = GameClock.use(() -> now)) {
            Player p = player(now - 3600000); candidate(p.getId()); getResources(p.getId()).setGold(80);
            Long id = recruit(p.getId()); tickService.tick(p.getId());
            assertEquals(0, getResources(p.getId()).getGold());
            assertEquals(80, officerRepository.findById(id).orElseThrow().getLoyalty());
            assertNull(getResources(p.getId()).getSalaryArrearsStartedAt());
            assertEquals(now, playerRepository.findById(p.getId()).orElseThrow().getLastTick());
        }
    }
    @Test void fundedRecruitmentDoesNotDeductHistoricalSalary() throws Exception {
        long now = GameClock.now();
        try (var clock = GameClock.use(() -> now)) {
            Player p = player(now - 3600000); candidate(p.getId());
            recruit(p.getId()); tickService.settleEconomyBeforeChange(p.getId());
            assertEquals(99920, getResources(p.getId()).getGold());
        }
    }
    @Test void recruitmentOrderUsesTheSameSalaryBoundaryAndConsumesOnlyOnce() throws Exception {
        long now = GameClock.now();
        try (var clock = GameClock.use(() -> now)) {
            Player p = player(now - 3600000); getResources(p.getId()).setGold(0);
            PlayerItem item = new PlayerItem(); item.setPlayerId(p.getId()); item.setItemKey("recruitOrd");
            item.setCount(1); item.setUpdatedAt(now); items.save(item);
            var response = depot.useItem(p.getId(), "recruitOrd", null, null);
            assertEquals(true, response.get("success"), response.toString());
            Long id = ((Number) response.get("officerId")).longValue();
            Officer recruited = officerRepository.findById(id).orElseThrow();
            int loyalty = recruited.getLoyalty(); tickService.settleEconomyBeforeChange(p.getId());
            assertEquals(loyalty, officerRepository.findById(id).orElseThrow().getLoyalty());
            assertNull(getResources(p.getId()).getSalaryArrearsStartedAt());
            assertEquals(false, depot.useItem(p.getId(), "recruitOrd", null, null).get("success"));
            assertEquals(1, officerRepository.findByPlayerId(p.getId()).size());
        }
    }
    @Test void dismissalPaysExistingSalaryBeforeRemovingTheOfficer() throws Exception {
        long now = GameClock.now();
        try (var clock = GameClock.use(() -> now)) {
            Player p = player(now - 3600000); Officer old = createOfficer(p.getId(), "idle", 0, 0, 0);
            old.setSalary(100);
            assertEquals(true, officers.dismiss(p.getId(), old.getId()).get("success"));
            assertEquals(99920, getResources(p.getId()).getGold());
            assertFalse(officerRepository.existsById(old.getId()));
            tickService.settleEconomyBeforeChange(p.getId()); assertEquals(99920, getResources(p.getId()).getGold());
        }
    }
    @Test void taxChangeDoesNotTaxThePastOrTouchOtherCityClock() throws Exception {
        long now = GameClock.now();
        try (var clock = GameClock.use(() -> now)) {
            Player p = player(now - 3600000); createBuilding(p.getId(), "house", 1); getResources(p.getId()).setGold(0);
            PlayerCity other = city(p.getId(), 1, now - 7200000, false);
            assertEquals(true, gameStateService.setTax(p.getId(), 100).get("success"));
            assertEquals(0, getResources(p.getId()).getGold());
            assertEquals(now - 7200000, other.getLastTick());
            tickService.tick(p.getId()); assertEquals(0, getResources(p.getId()).getGold());
        }
    }
    @Test void recruitingDuringExistingArrearsDoesNotInheritPastPenalties() throws Exception {
        long now = GameClock.now();
        try (var clock = GameClock.use(() -> now)) {
            Player p = player(now); candidate(p.getId()); getResources(p.getId()).setGold(80);
            Officer old = createOfficer(p.getId(), "idle", 0, 0, 0); old.setSalary(100);
            Resources stock = getResources(p.getId());
            stock.setSalaryArrearsStartedAt(now - 3600000); stock.setSalaryArrearsPenalties(700);
            Long id = recruit(p.getId()); Officer fresh = officerRepository.findById(id).orElseThrow();
            assertEquals(80, fresh.getLoyalty());
            try (var next = GameClock.use(() -> now + 59999)) { tickService.settleEconomyBeforeChange(p.getId()); }
            assertEquals(80, fresh.getLoyalty());
            try (var next = GameClock.use(() -> now + 60000)) { tickService.settleEconomyBeforeChange(p.getId()); }
            assertEquals(78, fresh.getLoyalty());
        }
    }
    @Test void subCityTaxAndRecruitmentSettleOnlyTheirOwnEconomy() throws Exception {
        long now = GameClock.now();
        try (var clock = GameClock.use(() -> now)) {
            Player p = player(now - 7200000); PlayerCity c = city(p.getId(), 1, now - 3600000, false);
            Resources stock = new Resources(); stock.setPlayerId(p.getId()); stock.setCitySlot(1); stock.setGold(1000);
            resourcesRepository.save(stock);
            Building house = createBuilding(p.getId(), "house", 1); house.setCitySlot(1);
            c.setCivilianPopulation(1000); c.setMorale(100);
            Academy a = new Academy(); a.setPlayerId(p.getId()); a.setCitySlot(1);
            a.setOfficers(JsonUtil.toJson(List.of(Map.of("name", "分城军官", "star", 1, "salary", 100, "loyalty", 80))));
            academies.save(a);
            try (var ignored = scope.enter(c)) {
                assertEquals(true, gameStateService.setTax(p.getId(), 100).get("success"));
                assertEquals(1000, stock.getGold());
                Long id = recruit(p.getId());
                assertEquals(1, officerRepository.findById(id).orElseThrow().getCitySlot());
                assertEquals(920, stock.getGold());
            }
            assertEquals(now, c.getLastTick()); assertEquals(now - 7200000, p.getLastTick());
            assertEquals(100000, getResources(p.getId()).getGold());
        }
    }
    @Test void prisonerAndMailArmyStartFoodAtTheGrantTime() throws Exception {
        long now = GameClock.now();
        for (boolean viaMail : List.of(false, true)) for (boolean managed : List.of(false, true)) for (int slot : List.of(0, 1)) {
            try (var clock = GameClock.use(() -> now)) {
                Player p = player(now - 3600000); PlayerCity c = city(p.getId(), slot, now - 3600000, managed);
                if (slot != 0) {
                    Resources stock = new Resources(); stock.setPlayerId(p.getId()); stock.setCitySlot(slot);
                    stock.setFood(10000); stock.setGold(100000); resourcesRepository.save(stock);
                }
                Building factory = createBuilding(p.getId(), "factory", 1); factory.setCitySlot(slot);
                try (var ignored = scope.enter(c)) {
                if (viaMail) {
                    Mail m = mail.sendSystem(p.getId(), "系统", "system", "test", "", List.of(Map.of("type", "infantry", "qty", 100)));
                    assertEquals(true, mail.claimAttach(p.getId(), m.getId()).get("success"));
                } else {
                    Building camp = createBuilding(p.getId(), "prisoner_camp", 1); camp.setCitySlot(slot);
                    PrisonerUnit w = new PrisonerUnit(); w.setPlayerId(p.getId()); w.setCityId(c.getId()); w.setSettlementId(1L);
                    w.setSourceId(UUID.randomUUID().toString()); w.setOriginalOwnerId(p.getId()); w.setType("infantry");
                    w.setOriginalCount(100); w.setCount(100); w.setCreatedAt(now - 3600000); captiveRows.save(w);
                    assertEquals(true, prisoners.operateInCity(p.getId(), c.getId(), "recruit", "infantry", 100, UUID.randomUUID().toString()).get("success"));
                }
                food.settle(c, now);
                assertEquals(10000, resourcesRepository.findByPlayerIdAndCitySlot(p.getId(), slot).orElseThrow().getFood());
                assertEquals(now, c.getGarrisonFoodAt());
                }
            }
        }
    }
    @Test void mixedMailSettlesExistingFoodBeforeApplyingResourcesAndArmy() throws Exception {
        long now = GameClock.now();
        try (var clock = GameClock.use(() -> now)) {
            Player p = player(now - 3600000); PlayerCity c = city(p.getId(), 0, now - 3600000, false);
            createBuilding(p.getId(), "factory", 1); createArmyUnit(p.getId(), "infantry", 100);
            Mail m = mail.sendSystem(p.getId(), "系统", "system", "test", "", List.of(
                    Map.of("type", "food", "qty", 500), Map.of("type", "infantry", "qty", 100)));
            assertEquals(true, mail.claimAttach(p.getId(), m.getId()).get("success"));
            food.settle(c, now); assertEquals(10400, getResources(p.getId()).getFood());
            assertEquals(200, armyUnitRepository.findByPlayerIdAndCitySlotAndType(p.getId(), 0, "infantry").get(0).getCount());
        }
    }
    @Test void wallDowngradeCannotLeaveExistingFortsOverCapacity() {
        Player p = player(GameClock.now()); createBuilding(p.getId(), "wall", 1);
        assertEquals(true, forts.build(p.getId(), "bunker", 3000).get("success"));
        assertEquals(false, buildService.dismantle(p.getId(), "wall", 0).get("success"));
        assertEquals(3000, forts.totalForts(p.getId())); assertTrue(constructionRepository.findByPlayerId(p.getId()).isEmpty());
    }
    @Test void wallDowngradeReservesTargetCapacityUntilCancelled() {
        Player p = player(GameClock.now()); createBuilding(p.getId(), "wall", 2);
        assertEquals(true, forts.build(p.getId(), "bunker", 3000).get("success"));
        assertEquals(true, buildService.dismantle(p.getId(), "wall", 0).get("success"));
        assertEquals(false, forts.build(p.getId(), "bunker", 1).get("success"));
        assertEquals(true, buildService.cancel(p.getId(), "wall", 0).get("success"));
        assertEquals(true, forts.build(p.getId(), "bunker", 1).get("success"));
    }
    @Test void historicalWallDowngradeCannotSilentlyRemoveFortsOrCapacity() {
        long now = GameClock.now(); Player p = player(now); createBuilding(p.getId(), "wall", 1);
        assertEquals(true, forts.build(p.getId(), "bunker", 3000).get("success"));
        Construction q = new Construction(); q.setPlayerId(p.getId()); q.setBuildingType("wall"); q.setTargetLevel(0);
        q.setStartAt(now - 60000); q.setFinishAt(now); constructionRepository.save(q);
        buildService.completeUpgrade(p.getId(), now);
        assertEquals(3000, forts.totalForts(p.getId())); assertEquals(3000, forts.fortCap(p.getId()));
        assertFalse(constructionRepository.existsById(q.getId()));
        assertEquals(1, buildingRepository.findByPlayerIdAndCitySlotAndType(p.getId(), 0, "wall").get(0).getLevel());
    }
    @Test void cancelProductionFreesTheLaneAndPreservesOrderDuration() throws Exception {
        long now = GameClock.now(); try (var clock = GameClock.use(() -> now)) {
            Player p = player(now); createBuilding(p.getId(), "factory", 1); createBuilding(p.getId(), "house", 1);
            Long first = order(p.getId(), 10), second = order(p.getId(), 10);
            var q = queues.findById(second).orElseThrow(); long duration = q.getFinishesAt() - q.getStartedAt();
            assertTrue(army.cancelProduction(p.getId(), first));
            q = queues.findById(second).orElseThrow(); assertEquals(now, q.getStartedAt());
            assertEquals(duration, q.getFinishesAt() - q.getStartedAt());
            assertFalse(army.cancelProduction(p.getId(), first));
        }
    }
    private ArmyProductionQueue queued(Long player, String unit, long start, long end) {
        ArmyProductionQueue q = new ArmyProductionQueue(); q.setPlayerId(player); q.setUnitType(unit);
        q.setBuildingType(com.wargame.model.constants.GameData.UNITS.get(unit).build());
        q.setUnitCount(10); q.setStartedAt(start); q.setFinishesAt(end);
        q.setDurationSeconds((int) ((end - start) / 1000)); q.setSpeedMultiplier(1.0); q.setCostPopulation(0);
        return queues.save(q);
    }
    @Test void cancellationKeepsParallelLanesOtherFacilitiesAndDurations() throws Exception {
        long now = GameClock.now();
        for (boolean waiting : List.of(false, true)) try (var clock = GameClock.use(() -> now)) {
            Player p = player(now); createBuilding(p.getId(), "factory", 5);
            var a = queued(p.getId(), "infantry", now, now + 100000);
            var b = queued(p.getId(), "infantry", now, now + 200000);
            var c = queued(p.getId(), "infantry", now + 100000, now + 400000);
            var d = queued(p.getId(), "infantry", now + 200000, now + 600000);
            var e = queued(p.getId(), "infantry", now + 400000, now + 900000);
            var aircraft = queued(p.getId(), "fighter", now + 500000, now + 600000);
            assertTrue(army.cancelProduction(p.getId(), waiting ? c.getId() : a.getId()));
            assertEquals(now, b.getStartedAt()); assertEquals(now + 200000, b.getFinishesAt());
            assertEquals(now + 200000, d.getStartedAt()); assertEquals(now + 600000, d.getFinishesAt());
            assertEquals(now + (waiting ? 100000 : 300000), e.getStartedAt());
            assertEquals(500000, e.getFinishesAt() - e.getStartedAt());
            assertEquals(now + 500000, aircraft.getStartedAt());
            if (waiting) { assertEquals(now, a.getStartedAt()); assertEquals(now + 100000, a.getFinishesAt()); }
            else { assertEquals(now, c.getStartedAt()); assertEquals(now + 300000, c.getFinishesAt()); }
        }
    }
    @Test void alreadyCompletedProductionCannotBeCancelledOrRefunded() throws Exception {
        long now = GameClock.now();
        try (var clock = GameClock.use(() -> now)) {
            Player p = player(now); var q = queued(p.getId(), "infantry", now - 100000, now);
            q.setCostSteel(200); q.setPrestigeReward(7);
            assertFalse(army.cancelProduction(p.getId(), q.getId()));
            assertEquals(1000000, getResources(p.getId()).getSteel());
            assertEquals(10, armyUnitRepository.findByPlayerIdAndCitySlotAndType(p.getId(), 0, "infantry").get(0).getCount());
            assertEquals(7, p.getPrestige());
            assertFalse(army.cancelProduction(p.getId(), q.getId())); assertEquals(7, p.getPrestige());
        }
    }
    @Test void constructionRefundUsesTheOpeningCostAfterResearchCompletes() throws Exception {
        long now = GameClock.now(); try (var clock = GameClock.use(() -> now)) {
            Player p = player(now); createBuilding(p.getId(), "command", 2); createBuilding(p.getId(), "farm", 1);
            createBuilding(p.getId(), "lab", 2); createBuilding(p.getId(), "refinery", 1);
            createBuilding(p.getId(), "exchange", 1); createBuilding(p.getId(), "depot", 1);
            var research = tech.startResearch(p.getId(), "log_build"); assertEquals(true, research.get("success"));
            long finish = ((Number) research.get("finishesAt")).longValue();
            try (var at = GameClock.use(() -> finish - 15000)) { assertEquals(true, buildService.upgrade(p.getId(), "house", 0).get("success")); }
            try (var at = GameClock.use(() -> finish)) {
                tech.settleCompletedResearchForCity(p.getId(), 0, finish); long before = getResources(p.getId()).getSteel();
                assertEquals(true, buildService.cancel(p.getId(), "house", 0).get("success"));
                assertEquals(60, getResources(p.getId()).getSteel() - before);
            }
        }
    }
}
