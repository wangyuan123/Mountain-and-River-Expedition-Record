package com.wargame.service;

import com.wargame.BaseServiceTest;
import com.wargame.model.constants.AcademyRecruitmentRules;
import com.wargame.model.constants.GameConstants;
import com.wargame.model.entity.Building;
import com.wargame.model.entity.Construction;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.TechResearchQueue;
import com.wargame.repository.TechResearchQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MilitaryTheoryTest extends BaseServiceTest {
    @Autowired private OfficerService officers;
    @Autowired private TechService research;
    @Autowired private PrerequisiteService prerequisites;
    @Autowired private TechResearchQueueRepository queues;
    @Autowired private CityScope cityScope;

    @Test
    void probabilityAddsThreePercentagePointsAndClampsBothLevels() {
        for (int level = 0; level <= 10; level++) {
            assertEquals(0.03 + level * 0.003, AcademyRecruitmentRules.fiveStarBatchChance(10, level), 1e-12);
        }
        assertEquals(0, AcademyRecruitmentRules.fiveStarBatchChance(0, 10));
        assertEquals(0, AcademyRecruitmentRules.fiveStarBatchChance(-1, 10));
        assertEquals(0.03, AcademyRecruitmentRules.fiveStarBatchChance(10, -1), 1e-12);
        assertEquals(0.06, AcademyRecruitmentRules.fiveStarBatchChance(99, 99), 1e-12);
        assertEquals(0.033, AcademyRecruitmentRules.fiveStarBatchChance(1, 10), 1e-12);
    }

    @Test
    void boostedRollUsesOneStrictBatchBoundaryAndHistoricalName() {
        for (double roll : new double[]{0.045, Math.nextDown(0.06), 0.06}) {
            RandomGenerator rng = mock(RandomGenerator.class);
            when(rng.nextDouble()).thenReturn(roll, 0.999999);
            when(rng.nextInt(OfficerService.ACADEMY_CANDIDATE_COUNT)).thenReturn(4);
            var candidates = officers.genAcademyCandidates(10, 10, rng);
            assertEquals(10, candidates.size());
            assertEquals(roll < 0.06 ? 1 : 0, candidates.stream()
                    .filter(c -> Integer.valueOf(5).equals(c.get("star"))).count());
            if (roll < 0.06) {
                assertTrue(GameConstants.OFFICER_NAMES.contains(candidates.get(4).get("name")));
                verify(rng).nextInt(10);
            } else {
                verify(rng, never()).nextInt(anyInt());
            }
        }
        RandomGenerator rng = mock(RandomGenerator.class);
        when(rng.nextDouble()).thenReturn(0.045, 0.999999);
        assertTrue(officers.genAcademyCandidates(10, 0, rng).stream()
                .noneMatch(c -> Integer.valueOf(5).equals(c.get("star"))));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void everyTargetLevelRequiresAllCompletedLocalFacilitiesBeforePayment(int target) {
        Long id = createTestPlayer().getId();
        giveResources(id, 1000000, 1000000, 1000000, 1000000, 1000000);
        createTechnology(id, "military_theory", target - 1);
        Building lab = createBuilding(id, "lab", Math.max(2, target));
        Building academy = createBuilding(id, "academy", target);
        Building staff = createBuilding(id, "staff", Math.max(0, target - 2));
        List<Building> required = target < 3 ? List.of(lab, academy) : List.of(lab, academy, staff);
        long gold = getResources(id).getGold();
        for (Building building : required) {
            int level = building.getLevel();
            building.setLevel(level - 1);
            buildingRepository.saveAndFlush(building);
            var denied = research.startResearch(id, "military_theory");
            assertEquals(false, denied.get("success"), building.getType());
            var missing = (List<Map<String, Object>>) denied.get("missingPrerequisites");
            assertTrue(missing.stream().anyMatch(m -> building.getType().equals(m.get("building"))));
            assertEquals(gold, getResources(id).getGold());
            assertTrue(queues.findByPlayerIdOrderByStartedAtAscIdAsc(id).isEmpty());
            building.setLevel(level);
            buildingRepository.saveAndFlush(building);
        }
        var started = research.startResearch(id, "military_theory");
        assertEquals(true, started.get("success"));
        assertEquals(target, started.get("targetLevel"));
        assertEquals(target - 1, research.getTechLevel(id, "military_theory"));
        var queue = queues.findByPlayerIdOrderByStartedAtAscIdAsc(id).get(0);
        assertEquals(prerequisites.snapshot("technologies", "military_theory", target), queue.getPrerequisiteRequirements());
        for (Building building : required) {
            assertEquals("研发中的军事理论", prerequisites.affectedTask(id, 0, building.getType(), 0, building.getLevel() - 1));
        }
        assertEquals(false, research.startResearch(id, "military_theory").get("success"));
    }

    @Test
    void anotherCityOrPendingUpgradeCannotSupplyTheMissingAcademyLevel() {
        Long id = createTestPlayer().getId();
        createBuilding(id, "lab", 10);
        createBuilding(id, "staff", 8);
        createBuilding(id, "academy", 9);
        Building elsewhere = new Building();
        elsewhere.setPlayerId(id);
        elsewhere.setType("academy");
        elsewhere.setLevel(10);
        elsewhere.setCitySlot(1);
        buildingRepository.saveAndFlush(elsewhere);
        Construction upgrade = new Construction();
        upgrade.setPlayerId(id);
        upgrade.setCitySlot(0);
        upgrade.setBuildingType("academy");
        upgrade.setSlot(0);
        upgrade.setTargetLevel(10);
        upgrade.setStartAt(System.currentTimeMillis());
        upgrade.setFinishAt(System.currentTimeMillis() + 60000);
        constructionRepository.saveAndFlush(upgrade);
        var missing = prerequisites.unmet(id, 0, "technologies", "military_theory", 10);
        assertEquals(1, missing.size());
        assertEquals("academy", missing.get(0).get("building"));
        assertEquals(9, missing.get(0).get("current"));
    }

    @Test
    void completedTheoryAppliesToRefreshAndStateWithLocalAcademyBase() {
        var world = createTestWorld();
        Long id = createTestPlayer().getId();
        createBuilding(id, "academy", 10);
        createTechnology(id, "military_theory", 9);
        giveResources(id, 1000, 1000, 1000, 1000, 1000);
        TechResearchQueue queue = new TechResearchQueue();
        queue.setPlayerId(id);
        queue.setCitySlot(0);
        queue.setTechType("military_theory");
        queue.setTargetLevel(10);
        queue.setStartedAt(1L);
        queue.setFinishesAt(2L);
        queue.setDurationSeconds(30);
        queues.saveAndFlush(queue);
        var result = officers.refreshAcademy(id);
        assertEquals(true, result.get("success"));
        assertEquals(10, research.getTechLevel(id, "military_theory"));
        assertEquals(0.06, result.get("fiveStarBatchChance"));
        var state = (Map<?, ?>) gameStateService.getGameState(id).get("academy");
        for (String key : List.of("fiveStarBatchChance", "militaryTheoryLevel", "militaryTheoryBonus", "baseFiveStarBatchChance")) {
            assertEquals(result.get(key), state.get(key), key);
        }
        assertEquals(0.03, state.get("baseFiveStarBatchChance"));
        assertEquals(0.03, state.get("militaryTheoryBonus"));
        PlayerCity city = new PlayerCity();
        city.setWorldId(world.getId());
        city.setOwnerId(id);
        city.setCitySlot(1);
        city.setName("军校分城");
        city.setX(20);
        city.setY(20);
        playerCityRepository.saveAndFlush(city);
        Building local = new Building();
        local.setPlayerId(id);
        local.setType("academy");
        local.setLevel(2);
        local.setCitySlot(1);
        buildingRepository.saveAndFlush(local);
        try (var scope = cityScope.enter(id, 1)) {
            var second = (Map<?, ?>) gameStateService.getGameState(id).get("academy");
            assertEquals(10, second.get("militaryTheoryLevel"));
            assertEquals(0.036, second.get("fiveStarBatchChance"));
        }
    }
}
