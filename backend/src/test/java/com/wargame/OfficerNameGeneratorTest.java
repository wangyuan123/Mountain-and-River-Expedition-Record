package com.wargame;

import com.wargame.model.constants.GameConstants;
import com.wargame.model.constants.OfficerNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfficerNameGeneratorTest {
    @Test
    void ordinaryOfficersUseChineseTransliterationsOfWesternGivenNamesThenSurnamesAtEveryStarLevel() {
        Random rng = new Random(20261018);
        Set<String> excluded = new HashSet<>(GameConstants.OFFICER_NAMES);
        for (int star = 1; star <= 4; star++) {
            for (int i = 0; i < 100; i++) {
                String name = OfficerNameGenerator.generateOfficerName(star, rng, excluded);
                assertTrue(name.matches("[一-龥]+·[一-龥]+"), name);
                String[] parts = name.split("·");
                assertTrue(OfficerNameGenerator.GIVEN_NAMES.contains(parts[0]), name);
                assertTrue(OfficerNameGenerator.SURNAMES.contains(parts[1]), name);
                assertFalse(OfficerNameGenerator.isFamousOfficer(name));
                assertTrue(excluded.add(name), "普通军官姓名不得与排除集合重复");
            }
        }
    }

    @Test
    void repeatedRandomCollisionsStillProduceDistinctChineseTransliteratedNames() {
        RandomGenerator rng = mock(RandomGenerator.class);
        when(rng.nextInt(anyInt())).thenReturn(0);
        Set<String> excluded = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            String name = OfficerNameGenerator.generateRandomNonFamousName(rng, excluded);
            assertTrue(name.matches("[一-龥]+·[一-龥]+"), name);
            if (i == 0) assertEquals("詹姆斯·史密斯", name);
            assertTrue(excluded.add(name), "随机重试耗尽后仍须遵守同批去重规则");
        }
    }

    @Test
    void exhaustedTransliteratedNamePoolFailsInsteadOfReturningAnExcludedName() {
        Set<String> excluded = new HashSet<>();
        for (String givenName : OfficerNameGenerator.GIVEN_NAMES) {
            for (String surname : OfficerNameGenerator.SURNAMES) {
                excluded.add(givenName + "·" + surname);
            }
        }
        assertThrows(IllegalStateException.class,
                () -> OfficerNameGenerator.generateRandomNonFamousName(new Random(0), excluded));
    }

    @Test
    void fiveStarOfficersKeepUsingTheFamousNamePoolAndRespectExclusions() {
        Set<String> excluded = new HashSet<>(GameConstants.OFFICER_NAMES);
        String available = GameConstants.OFFICER_NAMES.get(0);
        excluded.remove(available);
        assertEquals(available, OfficerNameGenerator.generateOfficerName(5, new Random(0), excluded));
        assertTrue(OfficerNameGenerator.isFamousOfficer(available));
    }
}
