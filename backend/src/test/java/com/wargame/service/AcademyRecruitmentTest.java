package com.wargame.service;

import com.wargame.BaseServiceTest;
import com.wargame.model.entity.Academy;
import com.wargame.model.entity.Building;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Resources;
import com.wargame.repository.AcademyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademyRecruitmentTest extends BaseServiceTest {
    @Autowired private OfficerService officers;
    @Autowired private AcademyRepository academies;
    @Autowired private CityScope cityScope;

    @Test
    void batchChanceGrowsLinearlyToThreePercent() {
        assertEquals(0, OfficerService.academyFiveStarBatchChance(0));
        assertEquals(0.003, OfficerService.academyFiveStarBatchChance(1), 1e-12);
        assertEquals(0.03, OfficerService.academyFiveStarBatchChance(10), 1e-12);
        assertEquals(0.03, OfficerService.academyFiveStarBatchChance(11), 1e-12);
        for (int level = 2; level <= 10; level++) {
            assertEquals(0.003,
                    OfficerService.academyFiveStarBatchChance(level) - OfficerService.academyFiveStarBatchChance(level - 1), 1e-12);
        }
    }

    @Test
    void aFailedBatchRollCannotProduceFiveStarsInIndividualRolls() {
        for (int level = 1; level <= 10; level++) {
            RandomGenerator rng = mock(RandomGenerator.class);
            when(rng.nextDouble()).thenReturn(OfficerService.academyFiveStarBatchChance(level), 0.999999);
            List<Map<String, Object>> list = officers.genAcademyCandidates(level, rng);
            assertEquals(OfficerService.ACADEMY_CANDIDATE_COUNT, list.size());
            assertTrue(list.stream().allMatch(o -> ((Number) o.get("star")).intValue() == 4));
            verify(rng, never()).nextInt(anyInt());
        }
    }

    @Test
    void aSuccessfulBatchContainsExactlyOneGenuineFiveStarInAnySlot() {
        for (int slot = 0; slot < OfficerService.ACADEMY_CANDIDATE_COUNT; slot++) {
            RandomGenerator rng = mock(RandomGenerator.class);
            when(rng.nextDouble()).thenReturn(Math.nextDown(0.03), 0.999999);
            when(rng.nextInt(OfficerService.ACADEMY_CANDIDATE_COUNT)).thenReturn(slot);
            List<Map<String, Object>> list = officers.genAcademyCandidates(10, rng);
            assertEquals(OfficerService.ACADEMY_CANDIDATE_COUNT, list.size());
            assertEquals(1, list.stream().filter(o -> ((Number) o.get("star")).intValue() == 5).count());
            Map<String, Object> legendary = list.get(slot);
            assertEquals(5, legendary.get("star"));
            int military = ((Number) legendary.get("military")).intValue();
            int defense = ((Number) legendary.get("defense")).intValue();
            int logistics = ((Number) legendary.get("logistics")).intValue();
            int knowledge = ((Number) legendary.get("knowledge")).intValue();
            int maxAttr = Math.max(Math.max(military, defense), Math.max(logistics, knowledge));
            assertTrue(maxAttr >= 111 && maxAttr <= 120);
            int minAttr = Math.min(Math.min(military, defense), Math.min(logistics, knowledge));
            assertTrue(minAttr >= 50 && minAttr <= 100);
            // 满级加99点后，主属性在210~219之间
            assertTrue(maxAttr + 99 >= 210 && maxAttr + 99 <= 219);
        }
    }

    @Test
    void thirtyRefreshesAreAllowedBeforeOneHourCooldownAndTheNextRound() {
        Long playerId = createTestPlayer().getId();
        createBuilding(playerId, "liaison", 10);
        giveResources(playerId, 1000, 1000, 1000, 500, 10000);
        int gold = getResources(playerId).getGold();
        assertEquals(false, officers.refreshAcademy(playerId).get("success"));
        assertEquals(gold, getResources(playerId).getGold());
        createBuilding(playerId, "academy", 1);
        for (int count = 1; count <= 30; count++) {
            long before = System.currentTimeMillis();
            Map<String, Object> refreshed = officers.refreshAcademy(playerId);
            assertEquals(true, refreshed.get("success"));
            assertEquals(OfficerService.ACADEMY_CANDIDATE_COUNT, ((List<?>) refreshed.get("list")).size());
            assertEquals(count, refreshed.get("refreshRoundCount"));
            assertEquals(count, refreshed.get("refreshDailyCount"));
            assertEquals(gold - 200 * count, getResources(playerId).getGold());
            long refreshAt = ((Number) refreshed.get("refreshAt")).longValue();
            if (count < 30) assertEquals(0L, refreshAt);
            else assertTrue(refreshAt >= before + 3600_000L && refreshAt <= System.currentTimeMillis() + 3600_000L);
        }
        List<Map<String, Object>> candidates = officers.getAcademyList(playerId);
        assertEquals(false, officers.refreshAcademy(playerId).get("success"));
        assertEquals(candidates, officers.getAcademyList(playerId));
        assertEquals(gold - 6000, getResources(playerId).getGold());
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setAcademyRefreshAt(System.currentTimeMillis() - 1);
        playerRepository.saveAndFlush(player);
        Map<String, Object> next = officers.refreshAcademy(playerId);
        assertEquals(true, next.get("success"));
        assertEquals(1, next.get("refreshRoundCount"));
        assertEquals(31, next.get("refreshDailyCount"));
        assertEquals(0L, next.get("refreshAt"));
    }

    @Test
    void dailyCapAllowsExactlyOneHundredSuccessfulRefreshesAndResetsNextDay() {
        Long playerId = createTestPlayer().getId();
        createBuilding(playerId, "academy", 1);
        giveResources(playerId, 1000, 1000, 1000, 500, 25000);
        for (int count = 1; count <= 100; count++) {
            if (count > 1 && (count - 1) % 30 == 0) {
                Player player = playerRepository.findById(playerId).orElseThrow();
                player.setAcademyRefreshAt(System.currentTimeMillis() - 1);
                playerRepository.saveAndFlush(player);
            }
            Map<String, Object> result = officers.refreshAcademy(playerId);
            assertEquals(true, result.get("success"), "第" + count + "次应可刷新");
            assertEquals(count, result.get("refreshDailyCount"));
        }
        List<Map<String, Object>> candidates = officers.getAcademyList(playerId);
        Map<String, Object> blocked = officers.refreshAcademy(playerId);
        assertEquals(false, blocked.get("success"));
        assertTrue(blocked.get("message").toString().contains("今日军校刷新已达100次"));
        assertEquals(candidates, officers.getAcademyList(playerId));
        assertEquals(5000, getResources(playerId).getGold());
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setAcademyRefreshDay(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
        playerRepository.saveAndFlush(player);
        Map<String, Object> next = officers.refreshAcademy(playerId);
        assertEquals(true, next.get("success"));
        assertEquals(1, next.get("refreshDailyCount"));
        assertEquals(11, next.get("refreshRoundCount"));
    }

    @Test
    void failedPaymentDoesNotUseQuotaAndOldSingleRefreshCooldownIsIgnored() {
        Long playerId = createTestPlayer().getId();
        createBuilding(playerId, "academy", 1);
        Academy academy = new Academy();
        academy.setPlayerId(playerId);
        academy.setRefreshAt(System.currentTimeMillis() + 3600_000L);
        academy.setOfficers("[]");
        academies.saveAndFlush(academy);
        giveResources(playerId, 0, 0, 0, 0, 199);
        assertEquals(false, officers.refreshAcademy(playerId).get("success"));
        assertEquals(0, playerRepository.findById(playerId).orElseThrow().getAcademyRefreshDailyCount());
        assertEquals(199, getResources(playerId).getGold());
        giveResources(playerId, 0, 0, 0, 0, 200);
        assertEquals(true, officers.refreshAcademy(playerId).get("success"));
        assertEquals(1, playerRepository.findById(playerId).orElseThrow().getAcademyRefreshDailyCount());
        assertEquals(0, getResources(playerId).getGold());
    }

    @Test
    void switchingCitiesDoesNotRestoreDailyQuota() {
        Player player = createTestPlayer();
        Long playerId = player.getId();
        createBuilding(playerId, "academy", 1);
        player.setAcademyRefreshDay(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        player.setAcademyRefreshDailyCount(99);
        player.setAcademyRefreshRoundCount(9);
        playerRepository.saveAndFlush(player);
        assertEquals(true, officers.refreshAcademy(playerId).get("success"));
        Building secondAcademy = createBuilding(playerId, "academy", 1);
        secondAcademy.setCitySlot(1);
        buildingRepository.saveAndFlush(secondAcademy);
        Resources resources = new Resources();
        resources.setPlayerId(playerId);
        resources.setCitySlot(1);
        resources.setGold(500);
        resourcesRepository.saveAndFlush(resources);
        try (CityScope.Scope ignored = cityScope.enter(playerId, 1)) {
            Map<String, Object> result = officers.refreshAcademy(playerId);
            assertEquals(false, result.get("success"));
            assertEquals(100, result.get("refreshDailyCount"));
            assertEquals(500, resourcesRepository.findByPlayerIdAndCitySlot(playerId, 1).orElseThrow().getGold());
        }
    }

    @Test
    void gameStateReturnsPersistedQuotaForTheAcademyPage() {
        createTestWorld();
        Long playerId = createTestPlayer().getId();
        gameStateService.initializeNewPlayer(playerId);
        createBuilding(playerId, "academy", 1);
        assertEquals(true, officers.refreshAcademy(playerId).get("success"));
        playerRepository.flush();
        Map<?, ?> academy = (Map<?, ?>) gameStateService.getGameState(playerId).get("academy");
        assertEquals(1, academy.get("refreshRoundCount"));
        assertEquals(1, academy.get("refreshDailyCount"));
        assertEquals(30, academy.get("refreshRoundLimit"));
        assertEquals(100, academy.get("refreshDailyLimit"));
        assertEquals(0L, academy.get("refreshAt"));
        assertTrue(((Number) academy.get("refreshDailyResetAt")).longValue() > System.currentTimeMillis());
    }

    @Test
    void allOfficersHaveExactlyOneSkillFromPool() {
        for (int star = 1; star <= 5; star++) {
            Map<String, Object> officer = officers.genOfficer(star);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> skills = (List<Map<String, Object>>) officer.get("skills");
            assertNotNull(skills);
            assertEquals(1, skills.size(), "军官应默认自带且只带1个技能");

            Map<String, Object> skill = skills.get(0);
            String skillId = (String) skill.get("id");
            assertTrue(com.wargame.model.constants.GameData.OFFICER_SKILLS.containsKey(skillId),
                    "技能必须来自当前技能库: " + skillId);
            int lv = ((Number) skill.get("lv")).intValue();
            assertTrue(lv >= 1, "技能等级至少为1级");
        }
    }

    @Test
    void fiveStarOfficersAlwaysHaveFamousNamesAndNonFiveStarNeverDo() {
        // 5星军官必须来自名将池
        for (int i = 0; i < 50; i++) {
            Map<String, Object> legend = officers.genOfficerWithStar(5);
            String name = (String) legend.get("name");
            assertTrue(com.wargame.model.constants.GameConstants.OFFICER_NAMES.contains(name),
                    "五星军官姓名必须属于名将池: " + name);
            assertTrue(com.wargame.model.constants.OfficerNameGenerator.isFamousOfficer(name));
        }

        // 1~4星普通军官姓名随机生成，绝不能是名将池中的名字
        for (int star = 1; star <= 4; star++) {
            for (int i = 0; i < 50; i++) {
                Map<String, Object> regular = officers.genOfficerWithStar(star);
                String name = (String) regular.get("name");
                assertFalse(com.wargame.model.constants.GameConstants.OFFICER_NAMES.contains(name),
                        star + "星普通军官姓名绝不能来自名将池: " + name);
                assertFalse(com.wargame.model.constants.OfficerNameGenerator.isFamousOfficer(name));
                assertTrue(name.length() >= 2, "随机生成的姓名长度至少为2字: " + name);
            }
        }
    }

    @Test
    void academyBatchCandidatesHaveDistinctNamesAndRespectStarNameRules() {
        for (int level = 1; level <= 10; level++) {
            List<Map<String, Object>> candidates = officers.genAcademyCandidates(level, ThreadLocalRandom.current());
            assertEquals(OfficerService.ACADEMY_CANDIDATE_COUNT, candidates.size());

            Set<String> namesInBatch = new HashSet<>();
            for (Map<String, Object> c : candidates) {
                int star = ((Number) c.get("star")).intValue();
                String name = (String) c.get("name");
                namesInBatch.add(name);

                if (star >= 5) {
                    assertTrue(com.wargame.model.constants.GameConstants.OFFICER_NAMES.contains(name),
                            "候选人中五星军官必须使用名将名字: " + name);
                } else {
                    assertFalse(com.wargame.model.constants.GameConstants.OFFICER_NAMES.contains(name),
                            "候选人中非五星军官不得使用名将名字: " + name);
                }
            }
            // 同一批候选人名单中名字不重复
            assertEquals(OfficerService.ACADEMY_CANDIDATE_COUNT, namesInBatch.size(), "同一批军校候选人中不得出现重名");
        }
    }
}
