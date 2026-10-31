package com.wargame;

import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.PlayerItem;
import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.EquipmentService;
import com.wargame.service.MailService;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WarSpeedOrderTest extends BaseServiceTest {
    @Autowired PlayerItemRepository items;
    @Autowired MailService mail;
    @Autowired EquipmentService equipment;
    @Autowired com.wargame.repository.PersonalWarRepository wars;
    private Long attackerId;
    private Long defenderId;
    private Long cityId;

    @BeforeEach void setup() {
        attackerId = createTestPlayer("war-order-attacker", 30).getId();
        defenderId = createTestPlayer("war-order-defender", 30).getId();
        PlayerCity city = new PlayerCity();
        city.setWorldId(createTestWorld().getId());
        city.setOwnerId(defenderId);
        city.setName("目标城");
        city.setX(45);
        city.setY(88);
        cityId = playerCityRepository.saveAndFlush(city).getId();
        items.saveAndFlush(new PlayerItem(null, attackerId, "warSpeedOrder", 2, GameClock.now()));
        assertEquals(true, worldService.declareWar(attackerId, cityId).get("success"));
        playerRepository.flush();
        playerCityRepository.flush();
    }

    private int stock() {
        return items.findByPlayerIdAndItemKey(attackerId, "warSpeedOrder").orElseThrow().getCount();
    }

    @Test void commanderKnowledgeRespectsMinimumAndSynchronizesBothSides() {
        createOfficer(attackerId, "commander", 0, 0, 300);
        officerRepository.flush();
        Map<String, Object> preview = worldService.previewWarOrder(attackerId, cityId);
        assertEquals(300, preview.get("knowledge"));
        assertEquals(0.8, (double) preview.get("ratio"), 0.000001);
        long declaredAt = playerRepository.findById(attackerId).orElseThrow().getWarDeclaredAt();
        assertEquals(declaredAt + 1_800_000, preview.get("warAt"));
        worldService.accelerateWar(attackerId, cityId, (String) preview.get("confirmationToken"));
        Player attacker = playerRepository.findById(attackerId).orElseThrow();
        Player defender = playerRepository.findById(defenderId).orElseThrow();
        assertEquals(declaredAt + 1_800_000, attacker.getWarAt());
        assertEquals(attacker.getWarAt(), defender.getWarAt());
        assertEquals(attacker.getWarEndAt(), defender.getWarEndAt());
        assertEquals(48 * 3_600_000L, attacker.getWarEndAt() - attacker.getWarAt());
        assertEquals(attacker.getWarAt(), playerCityRepository.findById(cityId).orElseThrow().getWarAt());
        assertTrue(attacker.isWarAccelerated());
        assertTrue(defender.isWarAccelerated());
        assertEquals(1, stock());
        assertEquals(2, mail.listSystem(attackerId).size());
        assertEquals(2, mail.listSystem(defenderId).size());
        assertThrows(IllegalArgumentException.class, () -> worldService.accelerateWar(
                attackerId, cityId, (String) preview.get("confirmationToken")));
        assertEquals(1, stock());
    }

    @Test void oldPreviewAndDefenderCannotSpendTheOrder() {
        Officer commander = createOfficer(attackerId, "commander", 0, 0, 100);
        officerRepository.flush();
        String token = (String) worldService.previewWarOrder(attackerId, cityId).get("confirmationToken");
        commander.setKnowledge(200);
        officerRepository.saveAndFlush(commander);
        assertThrows(IllegalArgumentException.class, () -> worldService.accelerateWar(attackerId, cityId, token));
        assertThrows(IllegalArgumentException.class, () -> worldService.previewWarOrder(defenderId, cityId));
        assertEquals(2, stock());
    }

    @Test void legacyWarWithoutDeclarationMetadataIsNotEligible() {
        wars.deleteAll(); wars.flush();
        Player attacker = playerRepository.findById(attackerId).orElseThrow();
        attacker.setWarDeclaredAt(null);
        playerRepository.saveAndFlush(attacker);
        assertThrows(IllegalArgumentException.class, () -> worldService.previewWarOrder(attackerId, cityId));
        assertEquals(2, stock());
    }

    @Test void equipmentKnowledgeInvalidatesPreviewAndAppliesToWarOrder() {
        Officer commander = createOfficer(attackerId, "commander", 0, 0, 100);
        commander.setLevel(100);
        officerRepository.saveAndFlush(commander);
        String oldToken = (String) worldService.previewWarOrder(attackerId, cityId).get("confirmationToken");
        OfficerEquipmentDef definition = OfficerEquipmentDef.setItems("marshal_knowledge").get(0);
        items.saveAndFlush(new PlayerItem(null, attackerId, definition.key(), 1, GameClock.now()));
        assertEquals(true, equipment.equip(attackerId, commander.getId(), definition.key()).get("success"));
        int knowledge = equipment.attributes(commander).knowledge();
        assertTrue(knowledge > 100);
        assertThrows(IllegalArgumentException.class, () -> worldService.accelerateWar(attackerId, cityId, oldToken));
        assertEquals(2, stock());
        Map<String, Object> preview = worldService.previewWarOrder(attackerId, cityId);
        assertEquals(knowledge, preview.get("knowledge"));
        assertEquals(Math.min(0.5 + knowledge * 0.001, 0.8), (double) preview.get("ratio"), 0.000001);
        worldService.accelerateWar(attackerId, cityId, (String) preview.get("confirmationToken"));
        assertEquals(1, stock());
    }

    @Test void legacyDisplayFieldsCannotOverrideIndependentWarAndExpiredWarCannotSpendOrder() {
        PlayerCity city = playerCityRepository.findById(cityId).orElseThrow();
        long originalEnd = city.getWarEndAt();
        city.setWarEndAt(originalEnd - 1);
        playerCityRepository.saveAndFlush(city);
        assertNotNull(worldService.previewWarOrder(attackerId, cityId).get("confirmationToken"));
        city.setWarEndAt(originalEnd);
        playerCityRepository.saveAndFlush(city);
        for (Long participant : java.util.List.of(attackerId, defenderId)) {
            Player player = playerRepository.findById(participant).orElseThrow();
            player.setWarEndAt(GameClock.now() - 1);
            playerRepository.saveAndFlush(player);
        }
        city.setWarEndAt(playerRepository.findById(attackerId).orElseThrow().getWarEndAt());
        playerCityRepository.saveAndFlush(city);
        var war = wars.findAll().get(0);
        war.setWarEndAt(GameClock.now() - 1); wars.saveAndFlush(war);
        assertThrows(IllegalArgumentException.class, () -> worldService.previewWarOrder(attackerId, cityId));
        assertEquals(2, stock());
    }
}
