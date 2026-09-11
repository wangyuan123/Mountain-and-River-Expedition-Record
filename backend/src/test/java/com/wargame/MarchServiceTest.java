package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MarchService 单元测试")
class MarchServiceTest extends BaseServiceTest {

    private Long playerId;
    private Long worldId;
    private WildTile wildTile;

    @BeforeEach
    void setUp() {
        Player player = createTestPlayer("marchplayer", 30);
        playerId = player.getId();

        WorldMap world = createTestWorld();
        worldId = world.getId();

        // Create a wild tile for conquer/gather tests
        wildTile = createWildTile(worldId, "forest", 15, 15, 1,
                Map.of("infantry", 10), 0);
    }

    @Test
    @DisplayName("创建出征: 有效军队应成功创建行军")
    void testCreateDispatch() {
        // Give player 100 infantry
        createArmyUnit(playerId, "infantry", 100);

        DispatchRequest req = new DispatchRequest(
                "wild", wildTile.getId(), "conquer",
                Map.of("infantry", 50),
                null, null
        );

        March march = marchService.createDispatch(playerId, req);

        assertNotNull(march);
        assertNotNull(march.getId());
        assertEquals("wild", march.getTargetKind());
        assertEquals(String.valueOf(wildTile.getId()), march.getTargetId());
        assertEquals("conquer", march.getAction());
        assertFalse(march.getReturning());

        // Verify army was deducted
        ArmyUnit unit = armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0);
        assertEquals(50, unit.getCount(), "出征后应剩余50步兵");

        // Verify march army is correct
        Map<String, Integer> marchArmy = JsonUtil.parseIntMap(march.getArmy());
        assertEquals(50, marchArmy.get("infantry"), "行军部队应为50步兵");
    }

    @Test
    @DisplayName("无效目标: 目标不存在应抛出异常")
    void testInvalidTarget() {
        createArmyUnit(playerId, "infantry", 100);

        DispatchRequest req = new DispatchRequest(
                "wild", 999999L, "conquer",
                Map.of("infantry", 50),
                null, null
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> marchService.createDispatch(playerId, req)
        );
        assertEquals("目标不存在", ex.getMessage());
    }

    @Test
    @DisplayName("兵力不足: 请求的兵种数量为0时应抛出异常")
    void testInsufficientArmy() {
        // Player has 0 infantry (don't create any)
        DispatchRequest req = new DispatchRequest(
                "wild", wildTile.getId(), "conquer",
                Map.of("infantry", 100),
                null, null
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> marchService.createDispatch(playerId, req)
        );
        assertEquals("请至少选择一种兵种出征", ex.getMessage());
    }

    @Test
    @DisplayName("行军到达: 征服野地的行军到达后应触发战斗")
    void testMarchArrival() {
        // Create army for the player
        createArmyUnit(playerId, "infantry", 100);

        // Create a wild tile with weak garrison
        WildTile targetTile = createWildTile(worldId, "mount", 12, 12, 1,
                Map.of("infantry", 5), 0);

        long now = System.currentTimeMillis();
        // Create a march that has already arrived (arriveAt in the past)
        createMarch(playerId, "wild", String.valueOf(targetTile.getId()),
                "山地 Lv.1", 10, 10, 12, 12,
                Map.of("infantry", 100), "conquer",
                now - 60000, now - 1000, false, false);

        // Process marches - should trigger battle
        marchService.processMarches(playerId, now);

        // The wild tile should be occupied (attacker wins with 100 vs 5)
        WildTile updated = wildTileRepository.findById(targetTile.getId()).orElseThrow();
        assertTrue(updated.getOccupied(), "野地应被占领");
        assertEquals(playerId, updated.getOccupiedBy(), "野地应被该玩家占领");

        // The march should now be returning (or deleted if no survivors)
        // Since attacker won with overwhelming force, march should be returning
        java.util.List<March> marches = marchRepository.findByPlayerId(playerId);
        boolean hasReturning = marches.stream().anyMatch(March::getReturning);
        assertTrue(hasReturning, "战斗胜利后行军应开始返程");
    }

    @Test
    @DisplayName("野地采集流程: 采集 -> 返程 -> 收取资源")
    void testWildGatherFlow() {
        // Create a wild tile with resources for gathering
        WildTile gatherTile = createWildTile(worldId, "grainfield", 20, 20, 1,
                null, 1000); // totalRes=1000
        // Mark as occupied (player owns it)
        gatherTile.setOccupied(true);
        gatherTile.setOccupiedBy(playerId);
        wildTileRepository.save(gatherTile);

        // Give player trucks (load=50 each)
        createArmyUnit(playerId, "truck", 10); // total load = 500

        long now = System.currentTimeMillis();
        long oneHourAgo = now - 3600_000L;

        // Step 1: Create a gather march that has arrived at the tile
        // (Army is "in the march" - deduct from player's home army)
        March gatherMarch = createMarch(playerId, "wild_gather",
                String.valueOf(gatherTile.getId()),
                "粮田 Lv.1", 10, 10, 20, 20,
                Map.of("truck", 10), "gather",
                oneHourAgo, now - 1000, false, false);
        // Simulate army being dispatched (deduct from home)
        ArmyUnit dispatchedTruck = armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0);
        dispatchedTruck.setCount(0);
        armyUnitRepository.save(dispatchedTruck);

        // Process: truck arrives, should start gathering
        marchService.processMarches(playerId, now);

        // Verify gathering started
        March gatheringMarch = marchRepository.findById(gatherMarch.getId()).orElseThrow();
        assertTrue(gatheringMarch.getGathering(), "行军应进入采集状态");
        assertTrue(gatheringMarch.getGatherEndAt() > now, "采集结束时间应在未来");
        assertNotNull(gatheringMarch.getGatherRes(), "应设置采集资源类型");
        assertTrue(gatheringMarch.getGatherAmount() > 0, "采集量应大于0");

        // gatherAmount = min(remaining=1000, load=500) = 500
        assertEquals(500, gatheringMarch.getGatherAmount(), "采集量应为min(1000, 500)=500");

        // Step 2: Fast-forward to after gather end - should start returning
        long gatherEndTime = gatheringMarch.getGatherEndAt();
        long afterGather = gatherEndTime + 1000;

        marchService.processMarches(playerId, afterGather);

        March returningMarch = marchRepository.findById(gatherMarch.getId()).orElseThrow();
        assertFalse(returningMarch.getGathering(), "采集完成后应停止采集");
        assertTrue(returningMarch.getReturning(), "采集完成后应开始返程");
        assertTrue(returningMarch.getArriveAt() > afterGather, "返程到达时间应在未来");

        // Step 3: Fast-forward to after return arrival - should collect resources
        long returnArrive = returningMarch.getArriveAt();
        long afterReturn = returnArrive + 1000;

        // Record resources before collection
        Resources beforeReturn = getResources(playerId);
        int foodBefore = beforeReturn.getFood();

        marchService.processMarches(playerId, afterReturn);

        // March should be deleted after return
        assertTrue(marchRepository.findById(gatherMarch.getId()).isEmpty(),
                "返程到达后行军应被删除");

        // Resources should increase
        Resources afterResources = getResources(playerId);
        assertTrue(afterResources.getFood() > foodBefore,
                "采集返程后粮食应增加");

        // Trucks should be returned to player
        ArmyUnit truckUnit = armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0);
        assertEquals(10, truckUnit.getCount(), "卡车应归还给玩家");

        // Wild tile mined should increase
        WildTile finalTile = wildTileRepository.findById(gatherTile.getId()).orElseThrow();
        assertTrue(finalTile.getMined() >= 500, "野地已采集量应增加");
    }

    @Test
    @DisplayName("取消出征: 应归还部队和携带资源并删除行军")
    void testCancelOutboundMarch() {
        createArmyUnit(playerId, "infantry", 50);
        Resources before = getResources(playerId);
        before.setFood(900);
        resourcesRepository.save(before);

        long now = System.currentTimeMillis();
        March march = createMarch(playerId, "wild", String.valueOf(wildTile.getId()),
                "森林 Lv.1", 10, 10, 15, 15,
                Map.of("infantry", 50), "conquer", now, now + 60_000, false, false);
        march.setCarryRes(JsonUtil.toJson(Map.of("food", 100, "steel", 0, "oil", 0, "rare", 0)));
        marchRepository.save(march);
        ArmyUnit dispatched = armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0);
        dispatched.setCount(0);
        armyUnitRepository.save(dispatched);

        marchService.cancelMarch(playerId, march.getId());

        assertTrue(marchRepository.findById(march.getId()).isEmpty());
        assertEquals(50, armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount());
        assertEquals(1000, getResources(playerId).getFood());
    }

    @Test
    @DisplayName("取消行军: 返程或采集阶段应被拒绝")
    void testCancelReturningOrGatheringMarchRejected() {
        long now = System.currentTimeMillis();
        March returning = createMarch(playerId, "wild", String.valueOf(wildTile.getId()),
                "森林 Lv.1", 15, 15, 10, 10,
                Map.of("infantry", 10), "conquer", now, now + 60_000, true, false);
        March gathering = createMarch(playerId, "wild_gather", String.valueOf(wildTile.getId()),
                "森林 Lv.1", 10, 10, 15, 15,
                Map.of("infantry", 10), "gather", now, now + 60_000, false, true);

        assertThrows(IllegalArgumentException.class, () -> marchService.cancelMarch(playerId, returning.getId()));
        assertThrows(IllegalArgumentException.class, () -> marchService.cancelMarch(playerId, gathering.getId()));
        assertTrue(marchRepository.findById(returning.getId()).isPresent());
        assertTrue(marchRepository.findById(gathering.getId()).isPresent());
    }
}
