package com.wargame;

import com.wargame.model.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BuildService 单元测试")
class BuildServiceTest extends BaseServiceTest {

    private Long playerId;

    @BeforeEach
    void setUp() {
        Player player = createTestPlayer("buildplayer", 30);
        playerId = player.getId();
    }

    @Test
    @DisplayName("升级建筑: 有效升级应创建施工记录")
    void testUpgradeBuilding() {
        // Give player plenty of resources
        giveResources(playerId, 5000, 5000, 5000, 5000, 5000);

        // Upgrade farm (multi-slot, slot 0, level 0->1)
        // farm baseCost = {steel: 80}, growth=1.5, curLv=0
        // costMul = pow(1.5, 0) * buildMul(1.0) = 1.0
        // cost = {steel: floor(80 * 1.0)} = {steel: 80}
        Map<String, Object> result = buildService.upgrade(playerId, "farm", 0);

        assertEquals(true, result.get("success"));
        assertEquals("farm", result.get("buildingType"));
        assertEquals(1, result.get("targetLevel"));
        assertNotNull(result.get("finishAt"));

        // Verify construction record was created
        List<Construction> constructions = constructionRepository.findByPlayerId(playerId);
        assertEquals(1, constructions.size());
        Construction c = constructions.get(0);
        assertEquals("farm", c.getBuildingType());
        assertEquals(1, c.getTargetLevel());
        assertEquals(0, c.getSlot());

        // Verify resources were deducted (steel: 5000 - 80 = 4920)
        Resources res = getResources(playerId);
        assertEquals(4920, res.getSteel(), "升级农田应扣除80钢铁");
    }

    @Test
    @DisplayName("资源不足: 资源不足时应返回失败")
    void testInsufficientResources() {
        // Give player insufficient steel (farm costs 80 steel)
        giveResources(playerId, 100, 50, 100, 100, 100);

        Map<String, Object> result = buildService.upgrade(playerId, "farm", 0);

        assertEquals(false, result.get("success"));
        assertEquals("资源不足", result.get("message"));

        // Verify no construction was created
        List<Construction> constructions = constructionRepository.findByPlayerId(playerId);
        assertEquals(0, constructions.size(), "资源不足时不应创建施工记录");

        // Verify resources were not deducted
        Resources res = getResources(playerId);
        assertEquals(50, res.getSteel(), "资源不足时不应扣除钢铁");
    }

    @Test
    @DisplayName("等级上限: 建筑达到市政厅等级上限时应返回失败")
    void testMaxLevel() {
        // Create command (市政厅) at level 1
        createBuilding(playerId, "command", 1);

        // Create farm at level 1 (slot 0)
        createBuilding(playerId, "farm", 1);

        // maxBuildingLevel = buildingLevel("command") = 1
        // max = min(10, max(1, 1)) = 1
        // curLv = buildingLevel(playerId, "farm", 0) = 1
        // curLv >= max => 1 >= 1 => true
        giveResources(playerId, 5000, 5000, 5000, 5000, 5000);

        Map<String, Object> result = buildService.upgrade(playerId, "farm", 0);

        assertEquals(false, result.get("success"));
        assertEquals("已达当前市政厅上限", result.get("message"));

        // Verify no construction was created
        List<Construction> constructions = constructionRepository.findByPlayerId(playerId);
        assertEquals(0, constructions.size(), "达到上限时不应创建施工记录");
    }

    @Test
    @DisplayName("取消施工: 取消应返还50%资源")
    void testCancelConstruction() {
        // Give player resources and upgrade farm
        giveResources(playerId, 0, 1000, 0, 0, 0);

        // Upgrade farm (costs 80 steel)
        Map<String, Object> upgradeResult = buildService.upgrade(playerId, "farm", 0);
        assertEquals(true, upgradeResult.get("success"));

        // After upgrade: steel = 1000 - 80 = 920
        Resources afterUpgrade = getResources(playerId);
        assertEquals(920, afterUpgrade.getSteel(), "升级后应剩余920钢铁");

        // Cancel the construction
        Map<String, Object> cancelResult = buildService.cancel(playerId, "farm", 0);

        assertEquals(true, cancelResult.get("success"));
        assertNotNull(cancelResult.get("refund"));

        // Verify refund: 50% of 80 = 40
        @SuppressWarnings("unchecked")
        Map<String, Integer> refund = (Map<String, Integer>) cancelResult.get("refund");
        assertEquals(40, refund.get("steel"), "取消应返还50%钢铁 = 40");

        // Verify resources after cancel: 920 + 40 = 960
        Resources afterCancel = getResources(playerId);
        assertEquals(960, afterCancel.getSteel(), "取消后钢铁应为 920 + 40 = 960");

        // Verify construction was deleted
        List<Construction> constructions = constructionRepository.findByPlayerId(playerId);
        assertEquals(0, constructions.size(), "取消后施工记录应被删除");
    }

    @Test
    @DisplayName("完成施工: 施工完成后建筑等级应提升")
    void testCompleteConstruction() {
        // Create a command building at level 0 (or no building)
        // Use single-slot building "command"
        // Create construction with finishAt in the past
        long now = System.currentTimeMillis();
        createConstruction(playerId, "command", 1, now - 60000, now - 1000, null);

        // Before completion: no command building exists
        List<Building> before = buildingRepository.findByPlayerIdAndType(playerId, "command");
        assertEquals(0, before.size(), "施工完成前不应有市政厅建筑");

        // Complete the construction
        buildService.completeUpgrade(playerId, now);

        // After completion: command building should exist at level 1
        List<Building> after = buildingRepository.findByPlayerIdAndType(playerId, "command");
        assertEquals(1, after.size(), "施工完成后应创建市政厅建筑");
        assertEquals(1, after.get(0).getLevel(), "市政厅等级应为1");

        // Verify construction was deleted
        List<Construction> constructions = constructionRepository.findByPlayerId(playerId);
        assertEquals(0, constructions.size(), "完成后施工记录应被删除");
    }
}
