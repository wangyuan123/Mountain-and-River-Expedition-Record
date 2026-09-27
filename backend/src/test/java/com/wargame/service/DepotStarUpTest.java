package com.wargame.service;

import com.wargame.BaseServiceTest;
import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DepotStarUpTest extends BaseServiceTest {

    @Autowired private DepotService depotService;
    @Autowired private PlayerItemRepository playerItemRepository;

    @Test
    void eachStarUsesItsFailureBoundaryAndConsumesOneItemPerAttempt() {
        Player player = createTestPlayer("star-odds", 30);
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        playerItemRepository.save(new PlayerItem(null, player.getId(), "starUp", 8, System.currentTimeMillis()));
        int[] failureRates = {10, 20, 30, 60};

        for (int star = 1; star <= 4; star++) {
            int beforeMilitary = officer.getMilitary();
            int beforeLogistics = officer.getLogistics();
            int beforeKnowledge = officer.getKnowledge();
            int rate = failureRates[star - 1];
            int beforeItems = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "starUp").orElseThrow().getCount();

            Map<String, Object> failed = depotService.useStarUp(player.getId(), officer.getId(), rate - 1);
            assertEquals(true, failed.get("success"));
            assertEquals(false, failed.get("upgraded"));
            assertEquals(star, officerRepository.findById(officer.getId()).orElseThrow().getStar());
            assertEquals(beforeMilitary, officerRepository.findById(officer.getId()).orElseThrow().getMilitary());
            assertEquals(beforeLogistics, officerRepository.findById(officer.getId()).orElseThrow().getLogistics());
            assertEquals(beforeKnowledge, officerRepository.findById(officer.getId()).orElseThrow().getKnowledge());
            assertEquals(beforeItems - 1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "starUp").orElseThrow().getCount());

            Map<String, Object> upgraded = depotService.useStarUp(player.getId(), officer.getId(), rate);
            assertEquals(true, upgraded.get("success"));
            assertEquals(true, upgraded.get("upgraded"));
            officer = officerRepository.findById(officer.getId()).orElseThrow();
            assertEquals(star + 1, officer.getStar());
            assertTrue(officer.getMilitary() > beforeMilitary);
            assertEquals(beforeItems - 2, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "starUp").orElseThrow().getCount());
        }
    }

    @Test
    void recruitOrdGeneratesFiveStarFamousGeneral() {
        Player player = createTestPlayer("recruit-test", 30);
        playerItemRepository.save(new PlayerItem(null, player.getId(), "recruitOrd", 1, System.currentTimeMillis()));

        Map<String, Object> resp = depotService.useItem(player.getId(), "recruitOrd", null, null);
        assertEquals(true, resp.get("success"));
        Long officerId = (Long) resp.get("officerId");
        assertNotNull(officerId);

        Officer officer = officerRepository.findById(officerId).orElseThrow();
        assertEquals(5, officer.getStar());
        assertTrue(com.wargame.model.constants.GameConstants.OFFICER_NAMES.contains(officer.getName()),
                "征募令生成的军官姓名必须属于五星名将池: " + officer.getName());
        assertTrue(com.wargame.model.constants.OfficerNameGenerator.isFamousOfficer(officer.getName()));
        // 验证四维属性均已生成
        assertTrue(officer.getMilitary() >= 50);
        assertTrue(officer.getDefense() >= 50);
        assertTrue(officer.getLogistics() >= 50);
        assertTrue(officer.getKnowledge() >= 50);
    }
}
