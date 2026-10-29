package com.wargame;

import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.OfficerService;
import com.wargame.service.DepotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OfficerLevelUpTest extends BaseServiceTest {

    @Autowired
    private OfficerService officerService;

    @Autowired
    private PlayerItemRepository playerItemRepository;

    @Autowired
    private DepotService depotService;

    @ParameterizedTest
    @CsvSource({"false,1", "true,1", "false,50", "true,50", "false,99", "true,99", "false,100", "true,100"})
    void maxExperienceBookDirectlyReachesCapAndConsumesOnlyOne(boolean fromDepot, int startingLevel) {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 90, 80, 70);
        officer.setLevel(startingLevel);
        officer.setExp(0L);
        officer.setAttrPoints(7);
        officerRepository.save(officer);
        PlayerItem item = new PlayerItem();
        item.setPlayerId(player.getId());
        item.setItemKey("expBookMax");
        item.setCount(2);
        item.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(item);

        Map<String, Object> result = fromDepot
                ? depotService.useItem(player.getId(), "expBookMax", officer.getId(), null)
                : officerService.useExpBook(player.getId(), officer.getId(), "expBookMax", 99);

        assertEquals(startingLevel < 100, result.get("success"));
        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(100, updated.getLevel());
        assertEquals(0L, updated.getExp());
        assertEquals(7 + 100 - startingLevel, updated.getAttrPoints());
        assertEquals(90, updated.getMilitary());
        assertEquals(80, updated.getLogistics());
        assertEquals(70, updated.getKnowledge());
        assertEquals(startingLevel < 100 ? 1 : 2,
                playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "expBookMax").orElseThrow().getCount());
    }

    @ParameterizedTest
    @CsvSource({"1,100000", "3,300000"})
    void advancedExperienceBooksGrantOneHundredThousandPerBook(int count, long gain) {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(1);
        officer.setExp(333L);
        officerRepository.save(officer);
        PlayerItem item = new PlayerItem();
        item.setPlayerId(player.getId());
        item.setItemKey("expBookAdv");
        item.setCount(5);
        item.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(item);

        Map<String, Object> result = officerService.useExpBook(player.getId(), officer.getId(), "expBookAdv", count);

        assertEquals(true, result.get("success"));
        assertEquals(gain, ((Number) result.get("gain")).longValue());
        assertEquals(333L + gain, result.get("exp"));
        assertEquals(count, result.get("count"));
        assertEquals(333L + gain, officerRepository.findById(officer.getId()).orElseThrow().getExp());
        assertEquals(5 - count,
                playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "expBookAdv").orElseThrow().getCount());
    }

    @ParameterizedTest
    @CsvSource({"1,500000", "3,1500000", "5000,2500000000"})
    void rapidExperienceBooksSupportBatchUseWithoutIntegerOverflow(int count, long gain) {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(1);
        officer.setExp(333L);
        officerRepository.saveAndFlush(officer);
        playerItemRepository.saveAndFlush(new PlayerItem(null, player.getId(), "expBookRapid", count + 1, 0L));
        var result = officerService.useExpBook(player.getId(), officer.getId(), "expBookRapid", count);
        assertEquals(true, result.get("success"));
        assertEquals(gain, ((Number) result.get("gain")).longValue());
        assertEquals(333L + gain, result.get("exp"));
        assertEquals(1, officerRepository.findById(officer.getId()).orElseThrow().getLevel());
        assertEquals(333L + gain, officerRepository.findById(officer.getId()).orElseThrow().getExp());
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "expBookRapid").orElseThrow().getCount());
    }

    @ParameterizedTest
    @CsvSource({"1,333", "99,600000", "100,0"})
    void depotRapidBookUsesSharedExperienceCurveAndRespectsLevelCap(int startingLevel, long initialExp) {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(startingLevel);
        officer.setExp(initialExp);
        officer.setAttrPoints(7);
        officerRepository.saveAndFlush(officer);
        playerItemRepository.saveAndFlush(new PlayerItem(null, player.getId(), "expBookRapid", 2, 0L));
        var result = depotService.useItem(player.getId(), "expBookRapid", officer.getId(), null);
        int level = startingLevel;
        long exp = initialExp;
        if (level < 100) {
            exp += 500_000;
            while (level < 100 && exp >= com.wargame.model.constants.OfficerExperienceRules.expNeeded(level)) {
                exp -= com.wargame.model.constants.OfficerExperienceRules.expNeeded(level++);
            }
            if (level == 100) exp = 0;
        }
        assertEquals(startingLevel < 100, result.get("success"));
        var updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(level, updated.getLevel());
        assertEquals(exp, updated.getExp());
        assertEquals(7 + level - startingLevel, updated.getAttrPoints());
        assertEquals(startingLevel < 100 ? 1 : 2,
                playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "expBookRapid").orElseThrow().getCount());
    }

    @Test void automaticBookSelectionUsesRapidBeforeMaxAndRejectsUnrelatedItems() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(1);
        officer.setExp(0L);
        officerRepository.saveAndFlush(officer);
        playerItemRepository.saveAndFlush(new PlayerItem(null, player.getId(), "expBookRapid", 2, 0L));
        playerItemRepository.saveAndFlush(new PlayerItem(null, player.getId(), "expBookMax", 1, 0L));
        playerItemRepository.saveAndFlush(new PlayerItem(null, player.getId(), "shield", 1, 0L));
        var invalid = officerService.useExpBook(player.getId(), officer.getId(), "shield", 1);
        assertEquals(false, invalid.get("success"));
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "shield").orElseThrow().getCount());
        var result = officerService.useExpBook(player.getId(), officer.getId());
        assertEquals(true, result.get("success"));
        assertEquals("expBookRapid", result.get("itemId"));
        assertEquals(500_000L, result.get("exp"));
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "expBookMax").orElseThrow().getCount());
    }

    @Test void maxBookPriceUsesWholeRapidBooksAtTwentyPercentDiscount() {
        long total = 0L;
        for (int level = 1; level < 100; level++) total += com.wargame.model.constants.OfficerExperienceRules.expNeeded(level);
        assertEquals(20_355_750L, total);
        assertEquals(41L, (total + 499_999L) / 500_000L);
        assertEquals(16_400, com.wargame.model.constants.OfficerExperienceRules.maxBookPrice());
    }

    @ParameterizedTest
    @CsvSource({"20,4000", "21,4275", "49,72875", "50,77500", "51,82425", "79,403025", "80,421000", "81,439725", "99,956325"})
    void singleUpgradeUsesNewCostAndPreservesExistingGrowth(int level, long needed) {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 90, 80, 70);
        officer.setLevel(level);
        officer.setExp(needed);
        officer.setAttrPoints(7);
        officerRepository.save(officer);

        Map<String, Object> result = officerService.levelUp(player.getId(), officer.getId(), false);

        assertEquals(true, result.get("success"));
        assertEquals(level + 1, result.get("level"));
        assertEquals(0L, result.get("exp"));
        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(level + 1, updated.getLevel());
        assertEquals(8, updated.getAttrPoints());
        assertEquals(90, updated.getMilitary());
        assertEquals(80, updated.getLogistics());
        assertEquals(70, updated.getKnowledge());
    }

    @ParameterizedTest
    @CsvSource({"21,4275", "50,77500", "81,439725", "99,956325"})
    void insufficientExperienceDoesNotAlterExistingOfficer(int level, long needed) {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 90, 80, 70);
        officer.setLevel(level);
        officer.setExp(needed - 1);
        officer.setAttrPoints(7);
        officerRepository.save(officer);

        for (boolean all : new boolean[] {false, true}) {
            Map<String, Object> result = officerService.levelUp(player.getId(), officer.getId(), all);
            assertEquals(false, result.get("success"));
            assertEquals("经验不足，升级需要 " + needed + " 经验", result.get("message"));
        }
        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(level, updated.getLevel());
        assertEquals(needed - 1, updated.getExp());
        assertEquals(7, updated.getAttrPoints());
    }

    @ParameterizedTest
    @CsvSource({"19,12074,21,4274", "19,12075,22,0", "49,232800,52,0", "79,1263750,82,0", "99,1000000,100,0", "1,20355749,99,956324", "1,20355750,100,0", "1,2281050,62,40375", "1,20000000,99,600575"})
    void bulkUpgradeCrossesStagesAndHandlesMaxLevel(int start, long exp, int expectedLevel, long remaining) {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(start);
        officer.setExp(exp);
        officer.setAttrPoints(7);
        officerRepository.save(officer);

        Map<String, Object> result = officerService.levelUp(player.getId(), officer.getId(), true);

        assertEquals(true, result.get("success"));
        assertEquals(expectedLevel, result.get("level"));
        assertEquals(remaining, result.get("exp"));
        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(expectedLevel, updated.getLevel());
        assertEquals(remaining, updated.getExp());
        assertEquals(7 + expectedLevel - start, updated.getAttrPoints());
        if (expectedLevel == 100) {
            assertEquals(false, officerService.levelUp(player.getId(), officer.getId(), true).get("success"));
        }
    }

    @ParameterizedTest
    @CsvSource({"50,expBook,333,50,10333", "50,expBookAdv,333,51,22833", "80,expBook,333,80,10333", "80,expBookAdv,333,80,100333", "99,expBookAdv,333,99,100333", "99,expBookAdv,856325,100,0"})
    void depotExperienceBooksUseSameCurve(int start, String itemId, long initialExp, int expectedLevel, long remaining) {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(start);
        officer.setExp(initialExp);
        officer.setAttrPoints(7);
        officerRepository.save(officer);
        PlayerItem item = new PlayerItem();
        item.setPlayerId(player.getId());
        item.setItemKey(itemId);
        item.setCount(2);
        item.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(item);

        Map<String, Object> result = depotService.useItem(player.getId(), itemId, officer.getId(), null);

        assertEquals(true, result.get("success"));
        assertEquals(expectedLevel, result.get("level"));
        assertEquals(remaining, result.get("exp"));
        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(expectedLevel, updated.getLevel());
        assertEquals(remaining, updated.getExp());
        assertEquals(7 + expectedLevel - start, updated.getAttrPoints());
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), itemId).orElseThrow().getCount());
    }

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
        assertEquals(1, updated.getAttrPoints());
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
        assertEquals(2, updated.getAttrPoints()); // 2 levels * 1 point
    }

    @Test
    public void testAssignAttrSuccess() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "commander", 50, 45, 40, 30);
        officer.setAttrPoints(10);
        officerRepository.save(officer);

        // Assign 5 points to military
        Map<String, Object> res1 = officerService.assignAttr(player.getId(), officer.getId(), "military", 5);
        assertTrue((Boolean) res1.get("success"));
        assertEquals(55, res1.get("newValue"));
        assertEquals(5, res1.get("attrPoints"));

        // Assign 2 points to defense
        Map<String, Object> resDef = officerService.assignAttr(player.getId(), officer.getId(), "defense", 2);
        assertTrue((Boolean) resDef.get("success"));
        assertEquals(47, resDef.get("newValue"));
        assertEquals(3, resDef.get("attrPoints"));

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
        assertEquals(47, updated.getDefense());
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
        officer.setLevel(5); // (5 - 1) * 1 = 4 points
        officer.setAttrPoints(0);
        officerRepository.save(officer);

        Map<String, Object> res = officerService.wash(player.getId(), officer.getId());
        assertTrue((Boolean) res.get("success"));
        assertEquals(4, res.get("attrPoints"));

        // Base for 2 star is 30 + 2 * 12 = 54
        assertEquals(54, res.get("military"));
        assertEquals(54, res.get("logistics"));
        assertEquals(54, res.get("knowledge"));

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(4, updated.getAttrPoints());
        assertEquals(54, updated.getMilitary());

        // Check gold deducted by 200: 500 - 200 = 300
        long gold = resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getGold();
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
    public void testUseExpBookAdds10000Exp() {
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
        assertEquals(10000L, res.get("exp"));
        assertEquals(10000L, ((Number) res.get("gain")).longValue());

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(10000L, updated.getExp());
        assertEquals(1, updated.getLevel());
    }

    @Test
    public void testUseExpBookAdvAdds100000Exp() {
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
        assertEquals(100000L, res.get("exp"));
        assertEquals(100000L, ((Number) res.get("gain")).longValue());

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(100000L, updated.getExp());
        assertEquals(1, updated.getLevel());

        PlayerItem itemUpdated = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "expBookAdv").orElseThrow();
        assertEquals(39, itemUpdated.getCount());
    }

    @Test
    public void testUseExpBookMaxInstantlyMaxLevel() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setLevel(1);
        officer.setExp(50L);
        officer.setAttrPoints(0);
        officerRepository.save(officer);

        PlayerItem item = new PlayerItem();
        item.setPlayerId(player.getId());
        item.setItemKey("expBookMax");
        item.setCount(2);
        item.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(item);

        Map<String, Object> res = officerService.useExpBook(player.getId(), officer.getId(), "expBookMax", 1);
        assertTrue((Boolean) res.get("success"));
        assertEquals(100, res.get("level"));
        assertEquals(0L, res.get("exp"));

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals(100, updated.getLevel());
        assertEquals(0L, updated.getExp());
        assertEquals(99, updated.getAttrPoints()); // 1级到100级获得 99*1=99 属性点

        PlayerItem itemUpdated = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "expBookMax").orElseThrow();
        assertEquals(1, itemUpdated.getCount()); // 消耗1本，剩余1本
    }

    @Test
    public void testAssignAttrClampedAt219() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "commander", 210, 50, 50);
        officer.setAttrPoints(20);
        officerRepository.save(officer);

        Map<String, Object> res = officerService.assignAttr(player.getId(), officer.getId(), "military", 15);
        assertTrue((Boolean) res.get("success"));
        assertEquals(9, res.get("added")); // 219 - 210 = 9 max add
        assertEquals(219, res.get("newValue"));
        assertEquals(11, res.get("attrPoints")); // 20 - 9 = 11

        // Trying to assign again should fail with limit reached
        Map<String, Object> resOver = officerService.assignAttr(player.getId(), officer.getId(), "military", 1);
        assertFalse((Boolean) resOver.get("success"));
        assertTrue(resOver.get("message").toString().contains("已达到上限 (219)"));
    }

    @Test
    public void testWashFiveStarOfficer() {
        Player player = createTestPlayer();
        giveResources(player.getId(), 1000, 1000, 1000, 500, 500);

        Officer officer = createOfficer(player.getId(), "commander", 150, 70, 60);
        officer.setStar(5);
        officer.setLevel(10); // (10 - 1) * 1 = 9 points
        officer.setAttrPoints(0);
        officerRepository.save(officer);

        Map<String, Object> res = officerService.wash(player.getId(), officer.getId());
        assertTrue((Boolean) res.get("success"));
        assertEquals(9, res.get("attrPoints"));

        // Military was highest, so it resets to 120 (mainBase), others reset to 75
        assertEquals(120, res.get("military"));
        assertEquals(75, res.get("defense"));
        assertEquals(75, res.get("logistics"));
        assertEquals(75, res.get("knowledge"));
    }
}
