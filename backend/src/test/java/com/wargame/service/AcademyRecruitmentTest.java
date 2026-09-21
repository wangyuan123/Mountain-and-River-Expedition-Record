package com.wargame.service;

import com.wargame.BaseServiceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademyRecruitmentTest extends BaseServiceTest {
    @Autowired private OfficerService officers;

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
            assertEquals(7, list.size());
            assertTrue(list.stream().allMatch(o -> ((Number) o.get("star")).intValue() == 4));
            verify(rng, never()).nextInt(anyInt());
        }
    }

    @Test
    void aSuccessfulBatchContainsExactlyOneGenuineFiveStarInAnySlot() {
        for (int slot = 0; slot < 7; slot++) {
            RandomGenerator rng = mock(RandomGenerator.class);
            when(rng.nextDouble()).thenReturn(Math.nextDown(0.03), 0.999999);
            when(rng.nextInt(7)).thenReturn(slot);
            List<Map<String, Object>> list = officers.genAcademyCandidates(10, rng);
            assertEquals(7, list.size());
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
    void refreshRequiresAcademyChargesOnceAndRespectsCooldown() {
        Long playerId = createTestPlayer().getId();
        createBuilding(playerId, "liaison", 10);
        int gold = getResources(playerId).getGold();
        assertEquals(false, officers.refreshAcademy(playerId).get("success"));
        assertEquals(gold, getResources(playerId).getGold());
        createBuilding(playerId, "academy", 1);
        Map<String, Object> refreshed = officers.refreshAcademy(playerId);
        assertEquals(true, refreshed.get("success"));
        assertEquals(7, ((List<?>) refreshed.get("list")).size());
        assertEquals(gold - 200, getResources(playerId).getGold());
        List<Map<String, Object>> candidates = officers.getAcademyList(playerId);
        assertEquals(false, officers.refreshAcademy(playerId).get("success"));
        assertEquals(candidates, officers.getAcademyList(playerId));
        assertEquals(gold - 200, getResources(playerId).getGold());
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
}
