package com.wargame;

import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.entity.*;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.OfficerService;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class GameRuleAdjustmentsTest extends BaseServiceTest {
    @Autowired private ArmyService army;
    @Autowired private OfficerService officers;
    @Autowired private ArmyProductionQueueRepository queues;
    @Autowired private PlayerItemRepository items;
    @Autowired private EntityManager entityManager;

    @Test
    void recruitingAndCancellingNeverGrantPrestigeAndCompletionGrantsItExactlyOnce() {
        var player = createTestPlayer();
        player.setCivilianPopulation(100);
        createBuilding(player.getId(), "factory", 1);
        createBuilding(player.getId(), "house", 1);
        giveResources(player.getId(), 10_000, 10_000, 10_000, 10_000, 10_000);
        var result = army.recruit(player.getId(), "infantry", 2);
        assertEquals(true, result.get("success"));
        var queue = queues.findById(((Number) result.get("queueId")).longValue()).orElseThrow();
        assertTrue(queue.getPrestigeReward() > 0);
        assertEquals(0, playerRepository.findById(player.getId()).orElseThrow().getPrestige());
        assertTrue(army.cancelProduction(player.getId(), queue.getId()));
        assertEquals(0, playerRepository.findById(player.getId()).orElseThrow().getPrestige());
        result = army.recruit(player.getId(), "infantry", 2);
        queue = queues.findById(((Number) result.get("queueId")).longValue()).orElseThrow();
        long deadline = queue.getFinishesAt();
        int reward = queue.getPrestigeReward();
        entityManager.flush(); entityManager.clear();
        army.completeProduction(player.getId(), deadline - 1);
        assertEquals(0, playerRepository.findById(player.getId()).orElseThrow().getPrestige());
        army.completeProduction(player.getId(), deadline);
        assertEquals(reward, playerRepository.findById(player.getId()).orElseThrow().getPrestige());
        assertEquals(2, armyUnitRepository.findByPlayerIdAndCitySlotAndType(player.getId(), 0, "infantry").get(0).getCount());
        army.completeProduction(player.getId(), deadline);
        assertEquals(reward, playerRepository.findById(player.getId()).orElseThrow().getPrestige());
    }

    @Test
    void paidOfficersKeepLoyaltyInEveryRole() throws Exception {
        var player = createTestPlayer("steady-loyalty", 0);
        long start = GameClock.now();
        player.setLastTick(start);
        giveResources(player.getId(), 0, 0, 0, 0, 100_000);
        for (String role : List.of("idle", "mayor", "commander", "commander_in_chief")) {
            var officer = createOfficer(player.getId(), role, 0, 0, 0);
            officer.setSalary(10);
        }
        try (var ignored = GameClock.use(() -> start + 3_600_000)) { tickService.tick(player.getId()); }
        for (var officer : officerRepository.findByPlayerId(player.getId())) assertEquals(80, officer.getLoyalty());
        assertNull(getResources(player.getId()).getSalaryArrearsStartedAt());
    }

    @Test
    void persistedArrearsSurviveReloadAndRepeatedTicksCannotRestartGracePeriod() throws Exception {
        var player = createTestPlayer("persistent-arrears", 0);
        var time = new AtomicLong(GameClock.now());
        long start = time.get();
        player.setLastTick(start);
        giveResources(player.getId(), 0, 0, 0, 0, 0);
        var officer = createOfficer(player.getId(), "idle", 0, 0, 0);
        officer.setSalary(3_600);
        try (var ignored = GameClock.use(time::get)) {
            time.addAndGet(30_000); tickService.tick(player.getId());
            entityManager.flush(); entityManager.clear();
            assertEquals(start, getResources(player.getId()).getSalaryArrearsStartedAt());
            assertEquals(80, officerRepository.findById(officer.getId()).orElseThrow().getLoyalty());
            time.addAndGet(30_000); tickService.tick(player.getId());
            entityManager.flush(); entityManager.clear();
            assertEquals(78, officerRepository.findById(officer.getId()).orElseThrow().getLoyalty());
            assertEquals(1, getResources(player.getId()).getSalaryArrearsPenalties());
            tickService.tick(player.getId());
            assertEquals(start, getResources(player.getId()).getSalaryArrearsStartedAt());
            time.addAndGet(10_000); tickService.tick(player.getId());
            assertEquals(74, officerRepository.findById(officer.getId()).orElseThrow().getLoyalty());
            giveResources(player.getId(), 0, 0, 0, 0, 1_000);
            time.addAndGet(5_000); tickService.tick(player.getId());
            assertNull(getResources(player.getId()).getSalaryArrearsStartedAt());
            assertEquals(0, getResources(player.getId()).getSalaryArrearsPenalties());
            assertEquals(74, officerRepository.findById(officer.getId()).orElseThrow().getLoyalty());
        }
    }

    @Test
    void randomBooksGiveFullLevelAtEveryStarAndLegacyUpgradeRequestsNeverConsumeStock() {
        var player = createTestPlayer("full-level-skills", 0);
        items.saveAndFlush(new PlayerItem(null, player.getId(), "skillBook", 5, 0L));
        items.saveAndFlush(new PlayerItem(null, player.getId(), "skillBook_frenzy", 2, 0L));
        for (int star = 1; star <= 5; star++) {
            var officer = createOfficer(player.getId(), "idle", 0, 0, 0);
            officer.setStar(star);
            officer.setSkills("[]");
            var response = officers.learnSkill(player.getId(), officer.getId());
            assertEquals(true, response.get("success"));
            assertEquals(5, response.get("level"));
            assertEquals(5, JsonUtil.parseList(officer.getSkills()).get(0).get("lv"));
            assertEquals(false, officers.upgradeSkill(player.getId(), officer.getId(), 0, "skillBook_frenzy").get("success"));
        }
        assertEquals(0, items.findByPlayerIdAndItemKey(player.getId(), "skillBook").orElseThrow().getCount());
        assertEquals(2, items.findByPlayerIdAndItemKey(player.getId(), "skillBook_frenzy").orElseThrow().getCount());
        for (String id : OfficerSkillDef.OFFICER_SKILLS.keySet()) {
            var officer = new Officer();
            officer.setSkills(JsonUtil.toJson(List.of(Map.of("id", id, "lv", 1))));
            assertEquals(5, JsonUtil.parseList(officer.getSkills()).get(0).get("lv"), id);
        }
    }
}
