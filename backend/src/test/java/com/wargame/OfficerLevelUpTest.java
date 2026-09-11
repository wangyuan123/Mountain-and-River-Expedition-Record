package com.wargame;

import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.OfficerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OfficerLevelUpTest extends BaseServiceTest {

    @Autowired
    private OfficerService officerService;

    @Autowired
    private PlayerItemRepository playerItemRepository;

    @Test
    public void testLevelUpSingleLevel() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "commander", 50, 40, 30);
        officer.setLevel(1);
        officer.setExp(800L); // enough for Lv.1 -> Lv.2 (needs 200)
        officerRepository.save(officer);

        Map<String, Object> res = officerService.levelUp(player.getId(), officer.getId(), false);
        assertTrue((Boolean) res.get("success"));
        assertEquals(2, res.get("level"));
        assertEquals(600L, res.get("exp")); // 800 - 200 = 600

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(2, updated.getLevel());
        assertEquals(600L, updated.getExp());
        assertEquals(50, updated.getMilitary());
        assertEquals(40, updated.getLogistics());
        assertEquals(30, updated.getKnowledge());
        assertEquals(4, updated.getAttrPoints());
    }

    @Test
    public void testLevelUpAllLevels() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "commander", 50, 40, 30);
        officer.setLevel(1);
        // Lv.1 needs 200 -> remaining 600
        // Lv.2 needs 400 -> remaining 200
        // Lv.3 needs 600 -> not enough
        officer.setExp(800L);
        officerRepository.save(officer);

        Map<String, Object> res = officerService.levelUp(player.getId(), officer.getId(), true);
        assertTrue((Boolean) res.get("success"));
        assertEquals(3, res.get("level"));
        assertEquals(200L, res.get("exp"));
        assertEquals(2, res.get("upgraded"));

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(3, updated.getLevel());
        assertEquals(200L, updated.getExp());
        assertEquals(50, updated.getMilitary());
        assertEquals(40, updated.getLogistics());
        assertEquals(30, updated.getKnowledge());
        assertEquals(8, updated.getAttrPoints()); // 2 levels * 4 points
    }

    @Test
    public void testAssignAttrSuccess() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "commander", 50, 40, 30);
        officer.setAttrPoints(8);
        officerRepository.save(officer);

        // Assign 5 points to military
        Map<String, Object> res1 = officerService.assignAttr(player.getId(), officer.getId(), "military", 5);
        assertTrue((Boolean) res1.get("success"));
        assertEquals(55, res1.get("newValue"));
        assertEquals(3, res1.get("attrPoints"));

        // Assign 2 points to logistics
        Map<String, Object> res2 = officerService.assignAttr(player.getId(), officer.getId(), "logistics", 2);
        assertTrue((Boolean) res2.get("success"));
        assertEquals(42, res2.get("newValue"));
        assertEquals(1, res2.get("attrPoints"));

        // Assign 1 point to knowledge
        Map<String, Object> res3 = officerService.assignAttr(player.getId(), officer.getId(), "knowledge", 1);
        assertTrue((Boolean) res3.get("success"));
        assertEquals(31, res3.get("newValue"));
        assertEquals(0, res3.get("attrPoints"));

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(55, updated.getMilitary());
        assertEquals(42, updated.getLogistics());
        assertEquals(31, updated.getKnowledge());
        assertEquals(0, updated.getAttrPoints());
    }

    @Test
    public void testAssignAttrInsufficientPoints() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "commander", 50, 40, 30);
        officer.setAttrPoints(2);
        officerRepository.save(officer);

        Map<String, Object> res = officerService.assignAttr(player.getId(), officer.getId(), "military", 5);
        assertFalse((Boolean) res.get("success"));
        assertTrue(res.get("message").toString().contains("点数不足"));
    }

    @Test
    public void testWashOfficer() {
        Player player = createTestPlayer();
        giveResources(player.getId(), 1000, 1000, 1000, 500, 500);

        Officer officer = createOfficer(player.getId(), "commander", 70, 50, 40);
        officer.setStar(2);
        officer.setLevel(5); // (5 - 1) * 4 = 16 points
        officer.setAttrPoints(0);
        officerRepository.save(officer);

        Map<String, Object> res = officerService.wash(player.getId(), officer.getId());
        assertTrue((Boolean) res.get("success"));
        assertEquals(16, res.get("attrPoints"));

        // Base for 2 star is 30 + 2 * 12 = 54
        assertEquals(54, res.get("military"));
        assertEquals(54, res.get("logistics"));
        assertEquals(54, res.get("knowledge"));

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(16, updated.getAttrPoints());
        assertEquals(54, updated.getMilitary());

        // Check gold deducted by 200: 500 - 200 = 300
        int gold = resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getGold();
        assertEquals(300, gold);
    }

    @Test
    public void testLevelUpInsufficientExp() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(1);
        officer.setExp(100L); // Lv.1 needs 200
        officerRepository.save(officer);

        Map<String, Object> res = officerService.levelUp(player.getId(), officer.getId(), false);
        assertFalse((Boolean) res.get("success"));
        assertTrue(res.get("message").toString().contains("经验不足"));
    }

    @Test
    public void testUseExpBookAdds500Exp() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(1);
        officer.setExp(0L);
        officerRepository.save(officer);

        PlayerItem item = new PlayerItem();
        item.setPlayerId(player.getId());
        item.setItemKey("expBook");
        item.setCount(2);
        item.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(item);

        Map<String, Object> res = officerService.useExpBook(player.getId(), officer.getId());
        assertTrue((Boolean) res.get("success"));
        assertEquals(500L, res.get("exp"));

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(500L, updated.getExp());
        assertEquals(1, updated.getLevel());
    }

    @Test
    public void testUseExpBookAdvAdds3000Exp() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(1);
        officer.setExp(0L);
        officerRepository.save(officer);

        PlayerItem item = new PlayerItem();
        item.setPlayerId(player.getId());
        item.setItemKey("expBookAdv");
        item.setCount(40);
        item.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(item);

        Map<String, Object> res = officerService.useExpBook(player.getId(), officer.getId(), "expBookAdv", 1);
        assertTrue((Boolean) res.get("success"));
        assertEquals(3000L, res.get("exp"));
        assertEquals(3000, res.get("gain"));

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(3000L, updated.getExp());
        assertEquals(1, updated.getLevel());

        PlayerItem itemUpdated = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "expBookAdv").orElseThrow();
        assertEquals(39, itemUpdated.getCount());
    }
}
