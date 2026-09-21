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

        // Verify prestige is NOT granted prematurely on upgrade start
        Player player = playerRepository.findById(playerId).orElseThrow();
        assertEquals(0, player.getPrestige(), "开始升级时不应提前发放声望");
    }

    @Test
    @DisplayName("开局可同时新建六项工程，第七项被拒绝且不扣资源，取消后可继续建造")
    void testSixConstructionTeams() {
        giveResources(playerId, 5000, 5000, 5000, 5000, 5000);
        for (int slot = 0; slot < 6; slot++) {
            assertEquals(true, buildService.upgrade(playerId, "farm", slot).get("success"));
        }
        int steelBefore = getResources(playerId).getSteel();
        Map<String, Object> blocked = buildService.upgrade(playerId, "farm", 6);
        assertEquals(false, blocked.get("success"));
        assertEquals("6 支施工队都在忙，请等待完成", blocked.get("message"));
        assertEquals(steelBefore, getResources(playerId).getSteel());
        assertEquals(6, constructionRepository.findByPlayerId(playerId).size());

        assertEquals(true, buildService.cancel(playerId, "farm", 0).get("success"));
        assertEquals(true, buildService.upgrade(playerId, "farm", 6).get("success"));
        assertEquals(6, constructionRepository.findByPlayerId(playerId).size());
    }

    @Test
    @DisplayName("拆除与新建共用六支施工队")
    void testDismantleSharesConstructionTeams() {
        giveResources(playerId, 5000, 5000, 5000, 5000, 5000);
        createBuilding(playerId, "refinery", 1);
        createBuilding(playerId, "oilfield", 1);
        for (int slot = 0; slot < 5; slot++) {
            assertEquals(true, buildService.upgrade(playerId, "farm", slot).get("success"));
        }
        assertEquals(true, buildService.dismantle(playerId, "refinery", 0).get("success"));
        Map<String, Object> blocked = buildService.dismantle(playerId, "oilfield", 0);
        assertEquals(false, blocked.get("success"));
        assertEquals("6 支施工队都在忙，请等待完成", blocked.get("message"));
        assertEquals(false, buildService.upgrade(playerId, "farm", 5).get("success"));
        assertEquals(6, constructionRepository.findByPlayerId(playerId).size());
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

        // Verify prestige is still 0 (no prestige leak or exploit from canceling)
        Player player = playerRepository.findById(playerId).orElseThrow();
        assertEquals(0, player.getPrestige(), "取消升级时不应产生声望变动");
    }

    @Test
    @DisplayName("完成施工: 施工完成后建筑等级应提升并结算发放声望")
    void testCompleteConstruction() {
        // Create a command building at level 0 (or no building)
        // Use single-slot building "command"
        // Create construction with finishAt in the past
        long now = System.currentTimeMillis();
        createConstruction(playerId, "command", 1, now - 60000, now - 1000, null);

        // Before completion: no command building exists
        List<Building> before = buildingRepository.findByPlayerIdAndType(playerId, "command");
        assertEquals(0, before.size(), "施工完成前不应有市政厅建筑");

        Player playerBefore = playerRepository.findById(playerId).orElseThrow();
        assertEquals(0, playerBefore.getPrestige(), "竣工前声望应为0");

        // Complete the construction
        buildService.completeUpgrade(playerId, now);

        // After completion: command building should exist at level 1
        List<Building> after = buildingRepository.findByPlayerIdAndType(playerId, "command");
        assertEquals(1, after.size(), "施工完成后应创建市政厅建筑");
        assertEquals(1, after.get(0).getLevel(), "市政厅等级应为1");

        // Verify construction was deleted
        List<Construction> constructions = constructionRepository.findByPlayerId(playerId);
        assertEquals(0, constructions.size(), "完成后施工记录应被删除");

        // Verify prestige is granted upon completion
        Player playerAfter = playerRepository.findById(playerId).orElseThrow();
        assertTrue(playerAfter.getPrestige() > 0, "施工完成后应结算发放声望");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"farm", "refinery", "oilfield", "raremine"})
    void resourceAreaAllows32OfAnyType(String type) {
        createBuilding(playerId, "command", 10);
        giveResources(playerId, 100000, 100000, 100000, 100000, 100000);
        for (int slot = 0; slot < 32; slot++) {
            assertEquals(true, buildService.upgrade(playerId, type, slot).get("success"), "栋数: " + (slot + 1));
            buildService.completeUpgrade(playerId, System.currentTimeMillis() + 60000);
        }
        assertEquals(32, buildingRepository.findByPlayerIdAndType(playerId, type).size());
        assertEquals(false, buildService.upgrade(playerId, "farm".equals(type) ? "refinery" : "farm", 0).get("success"));
    }

    @Test
    void mixedResourceBuildingsShare32SlotsAndCanStillUpgrade() {
        createBuilding(playerId, "command", 10);
        giveResources(playerId, 100000, 100000, 100000, 100000, 100000);
        for (String type : List.of("farm", "refinery", "oilfield", "raremine")) {
            for (int slot = 0; slot < 8; slot++) {
                Building building = new Building();
                building.setPlayerId(playerId);
                building.setType(type);
                building.setSlot(slot);
                building.setLevel(1);
                buildingRepository.save(building);
            }
        }
        int steelBefore = getResources(playerId).getSteel();
        assertEquals(false, buildService.upgrade(playerId, "farm", 8).get("success"));
        assertEquals(steelBefore, getResources(playerId).getSteel());
        assertEquals(true, buildService.upgrade(playerId, "farm", 0).get("success"));
    }

    @Test
    void newConstructionReservesLastResourceSlotAndCancellationReleasesIt() {
        createBuilding(playerId, "command", 10);
        giveResources(playerId, 100000, 100000, 100000, 100000, 100000);
        for (int slot = 0; slot < 31; slot++) {
            Building building = new Building();
            building.setPlayerId(playerId);
            building.setType("farm");
            building.setSlot(slot);
            building.setLevel(1);
            buildingRepository.save(building);
        }
        assertEquals(true, buildService.upgrade(playerId, "refinery", 0).get("success"));
        assertEquals(false, buildService.upgrade(playerId, "oilfield", 0).get("success"));
        assertEquals(true, buildService.cancel(playerId, "refinery", 0).get("success"));
        assertEquals(true, buildService.upgrade(playerId, "oilfield", 0).get("success"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,12", "1,14", "5,22", "9,30", "10,32", "11,32"})
    void resourceCapacityGrowsWithTownHallAndCapsAt32(int commandLevel, int capacity) {
        Building command = createBuilding(playerId, "command", commandLevel);
        giveResources(playerId, 100000, 100000, 100000, 100000, 100000);
        for (int slot = 0; slot < capacity - 1; slot++) {
            Building building = new Building();
            building.setPlayerId(playerId);
            building.setType("farm");
            building.setSlot(slot);
            building.setLevel(1);
            buildingRepository.save(building);
        }
        assertEquals(true, buildService.upgrade(playerId, "refinery", 0).get("success"));
        assertEquals(false, buildService.upgrade(playerId, "oilfield", 0).get("success"));
        if (commandLevel < 10) {
            command.setLevel(commandLevel + 1);
            buildingRepository.save(command);
            assertEquals(true, buildService.upgrade(playerId, "oilfield", 0).get("success"));
        }
    }

    @Test
    @DisplayName("拆除2级建筑: 耗时与升2级相同，完工后降为1级并返还资源")
    void testDismantleLevel2ToLevel1() {
        createBuilding(playerId, "command", 5);
        Building farm = new Building();
        farm.setPlayerId(playerId);
        farm.setType("farm");
        farm.setSlot(0);
        farm.setLevel(2);
        buildingRepository.save(farm);

        giveResources(playerId, 1000, 1000, 1000, 1000, 1000);
        int steelBefore = getResources(playerId).getSteel();

        // 拆除 2级农田 (slot 0)
        Map<String, Object> result = buildService.dismantle(playerId, "farm", 0);
        assertEquals(true, result.get("success"));
        assertEquals(1, result.get("targetLevel"));

        // 施工记录应存在，targetLevel=1
        List<Construction> constructions = constructionRepository.findByPlayerId(playerId);
        assertEquals(1, constructions.size());
        assertEquals(1, constructions.get(0).getTargetLevel());

        // 模拟施工时间结束完工
        long finishAt = constructions.get(0).getFinishAt();
        buildService.completeUpgrade(playerId, finishAt + 1000L);

        // 验证建筑等级变为 1
        Building afterBld = buildingRepository.findByPlayerIdAndTypeOrderByIdAsc(playerId, "farm").get(0);
        assertEquals(1, afterBld.getLevel(), "2级建筑拆除后应降至1级");

        // 验证回收了 30% 资源
        int steelAfter = getResources(playerId).getSteel();
        assertTrue(steelAfter > steelBefore, "拆除完成后应回收部分资源");
    }

    @Test
    @DisplayName("拆除1级建筑: 完工后建筑彻底消失释放卡槽")
    void testDismantleLevel1ToDisappear() {
        createBuilding(playerId, "command", 5);
        Building farm = new Building();
        farm.setPlayerId(playerId);
        farm.setType("farm");
        farm.setSlot(0);
        farm.setLevel(1);
        buildingRepository.save(farm);

        giveResources(playerId, 1000, 1000, 1000, 1000, 1000);

        // 拆除 1级农田
        Map<String, Object> result = buildService.dismantle(playerId, "farm", 0);
        assertEquals(true, result.get("success"));
        assertEquals(0, result.get("targetLevel"));

        // 模拟施工完工
        long finishAt = ((Long) result.get("finishAt"));
        buildService.completeUpgrade(playerId, finishAt + 1000L);

        // 验证建筑记录已彻底删除
        List<Building> farms = buildingRepository.findByPlayerIdAndType(playerId, "farm");
        assertTrue(farms.isEmpty(), "1级建筑拆除后应彻底删除");
    }

    @Test
    @DisplayName("拆除市政厅保护: 市政厅不可拆除")
    void testCannotDismantleCommandCenter() {
        createBuilding(playerId, "command", 2);
        Map<String, Object> result = buildService.dismantle(playerId, "command", 0);
        assertEquals(false, result.get("success"));
        assertEquals("市政厅为核心枢纽，不可拆除", result.get("message"));
    }

    @Test
    @DisplayName("取消拆除: 取消后建筑保留原等级，不产生多余退款")
    void testCancelDismantle() {
        createBuilding(playerId, "command", 5);
        Building farm = new Building();
        farm.setPlayerId(playerId);
        farm.setType("farm");
        farm.setSlot(0);
        farm.setLevel(2);
        buildingRepository.save(farm);

        giveResources(playerId, 1000, 1000, 1000, 1000, 1000);
        int steelBefore = getResources(playerId).getSteel();

        // 开始拆除
        buildService.dismantle(playerId, "farm", 0);
        assertEquals(1, constructionRepository.findByPlayerId(playerId).size());

        // 取消拆除
        Map<String, Object> cancelRes = buildService.cancel(playerId, "farm", 0);
        assertEquals(true, cancelRes.get("success"));
        assertEquals("已取消拆除", cancelRes.get("message"));

        // 施工队列已清空
        assertEquals(0, constructionRepository.findByPlayerId(playerId).size());

        // 建筑依然保持 2 级
        Building bld = buildingRepository.findByPlayerIdAndTypeOrderByIdAsc(playerId, "farm").get(0);
        assertEquals(2, bld.getLevel());

        // 资源未产生变化
        assertEquals(steelBefore, getResources(playerId).getSteel());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"house", "depot", "factory"})
    void militaryAreaAllowsFreeAllocationOfRepeatableBuildings(String type) {
        createBuilding(playerId, "command", 10);
        giveResources(playerId, 100000, 100000, 100000, 100000, 100000);
        for (int slot = 0; slot < 30; slot++) {
            Building building = createBuilding(playerId, type, 1);
            building.setSlot(slot);
            buildingRepository.save(building);
        }
        // 市政厅1栋 + 同类30栋 + 新建1栋 = 军事区32栋。
        assertEquals(true, buildService.upgrade(playerId, type, 30).get("success"));
        int steel = getResources(playerId).getSteel();
        assertEquals(false, buildService.upgrade(playerId, "radar", 0).get("success"));
        assertEquals(steel, getResources(playerId).getSteel());
        buildService.completeUpgrade(playerId, System.currentTimeMillis() + 60000);
        assertEquals(31, buildingRepository.findByPlayerIdAndType(playerId, type).size());
        assertEquals(true, buildService.upgrade(playerId, type, 0).get("success"));
        assertEquals(true, buildService.cancel(playerId, type, 0).get("success"));
        assertEquals(true, buildService.dismantle(playerId, type, 30).get("success"));
        buildService.completeUpgrade(playerId, System.currentTimeMillis() + 86400000);
        assertEquals(true, buildService.upgrade(playerId, "radar", 0).get("success"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,14", "5,22", "10,32"})
    void militaryQuotaIncludesSingleBuildingsAndPendingConstruction(int level, int capacity) {
        createBuilding(playerId, "command", level);
        giveResources(playerId, 100000, 100000, 100000, 100000, 100000);
        for (int slot = 0; slot < capacity - 2; slot++) {
            Building building = createBuilding(playerId, "house", 1);
            building.setSlot(slot);
            buildingRepository.save(building);
        }
        assertEquals(true, buildService.upgrade(playerId, "radar", 0).get("success"));
        assertEquals(false, buildService.upgrade(playerId, "depot", 0).get("success"));
        assertEquals(true, buildService.cancel(playerId, "radar", 0).get("success"));
        assertEquals(true, buildService.upgrade(playerId, "depot", 0).get("success"));
        // 两区独立计数：军事区满额不影响资源区。
        assertEquals(true, buildService.upgrade(playerId, "farm", 0).get("success"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"lightfactory", "heavyfactory", "port", "academy", "staff", "lab", "radar", "wall", "apron", "liaison", "transit", "exchange"})
    void functionalBuildingsRemainSingleInstance(String type) {
        createBuilding(playerId, "command", 10);
        createBuilding(playerId, type, 1);
        giveResources(playerId, 100000, 100000, 100000, 100000, 100000);
        assertEquals(1, com.wargame.model.constants.BuildingDef.BUILDINGS.get(type).slots());
        // 指定其他槽位依然升级原建筑，不会创建第二栋。
        assertEquals(true, buildService.upgrade(playerId, type, 1).get("success"));
        buildService.completeUpgrade(playerId, System.currentTimeMillis() + 86400000);
        List<Building> buildings = buildingRepository.findByPlayerIdAndType(playerId, type);
        assertEquals(1, buildings.size());
        assertEquals(2, buildings.get(0).getLevel());
    }

}
