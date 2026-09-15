package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

        // 验证野地征服战报记录了野地占领结果
        java.util.List<com.wargame.model.entity.ScoutReport> reports = scoutReportRepository.findByPlayerId(playerId);
        assertFalse(reports.isEmpty(), "应生成战报");
        com.wargame.model.entity.ScoutReport lastReport = reports.get(reports.size() - 1);
        assertTrue(lastReport.getData().contains("\"wildConquered\":true"), "战报应包含 wildConquered=true");
        assertTrue(lastReport.getData().contains("已占领"), "战报标题应包含已占领");
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

    @ParameterizedTest
    @CsvSource({"wild,conquer,infantry", "player,scout,scout", "wild_gather,gather,truck", "player,transport,truck", "player,rebase,truck"})
    @DisplayName("途中撤回: 保留返城行军，到达后部队资源和军官只归还一次")
    void testCancelOutboundMarch(String kind, String action, String unit) {
        createArmyUnit(playerId, unit, 50);
        Officer commander = createOfficer(playerId, "march", 30, 20, 10);
        Resources before = getResources(playerId);
        before.setFood(900);
        resourcesRepository.save(before);

        long now = System.currentTimeMillis();
        long outboundStart = now - 20_000;
        March march = createMarch(playerId, kind, String.valueOf(wildTile.getId()),
                "森林 Lv.1", 10, 10, 15, 15,
                Map.of(unit, 50), action, outboundStart, now + 40_000, false, false);
        march.setCommanderId(commander.getId());
        march.setCarryRes(JsonUtil.toJson(Map.of("food", 100, "steel", 0, "oil", 0, "rare", 0)));
        marchRepository.save(march);
        ArmyUnit dispatched = armyUnitRepository.findByPlayerIdAndType(playerId, unit).get(0);
        dispatched.setCount(0);
        armyUnitRepository.save(dispatched);

        marchService.cancelMarch(playerId, march.getId());

        March returning = marchRepository.findById(march.getId()).orElseThrow();
        assertTrue(returning.getReturning());
        assertEquals(10, returning.getTargetX());
        assertEquals(10, returning.getTargetY());
        assertEquals(15, returning.getFromX());
        assertEquals(15, returning.getFromY());
        assertEquals(60_000L, returning.getArriveAt() - returning.getStartAt());
        long recalledAt = (returning.getArriveAt() + outboundStart) / 2;
        assertTrue(recalledAt >= now && recalledAt <= System.currentTimeMillis());
        assertEquals(0, dispatched.getCount());
        assertEquals(900, getResources(playerId).getFood());
        assertEquals("march", commander.getRole());
        assertThrows(IllegalArgumentException.class, () -> marchService.cancelMarch(playerId, march.getId()));

        marchService.processMarches(playerId, returning.getArriveAt() - 1);
        assertTrue(marchRepository.findById(march.getId()).isPresent());
        assertEquals(0, dispatched.getCount());
        assertTrue(scoutReportRepository.findByPlayerId(playerId).isEmpty());
        long returnedAt = returning.getArriveAt();
        marchService.processMarches(playerId, returnedAt);
        marchService.processMarches(playerId, returnedAt + 1);
        assertTrue(marchRepository.findById(march.getId()).isEmpty());
        assertEquals(50, armyUnitRepository.findByPlayerIdAndType(playerId, unit).get(0).getCount());
        assertEquals(1000, getResources(playerId).getFood());
        assertEquals("idle", officerRepository.findById(commander.getId()).orElseThrow().getRole());
        assertTrue(scoutReportRepository.findByPlayerId(playerId).isEmpty());
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

    @Test
    @DisplayName("侦查任务: 敌方侦察机为0时我方零损失并成功获得情报")
    void testScoutMissionZeroEnemyScouts() {
        WildTile targetTile = createWildTile(worldId, "lake", 18, 18, 1,
                Map.of("infantry", 10), 0);

        long now = System.currentTimeMillis();
        createMarch(playerId, "wild", String.valueOf(targetTile.getId()),
                "湖泊 Lv.1", 10, 10, 18, 18,
                Map.of("scout", 928), "scout",
                now - 60000, now - 1000, false, false);

        marchService.processMarches(playerId, now);

        java.util.List<March> marches = marchRepository.findByPlayerId(playerId);
        assertEquals(1, marches.size());
        March returning = marches.get(0);
        assertTrue(returning.getReturning(), "侦查成功后应返程");
        Map<String, Integer> returningArmy = JsonUtil.parseIntMap(returning.getArmy());
        assertEquals(928, returningArmy.get("scout"), "敌方无侦察机时我方侦察机应无损失");

        java.util.List<ScoutReport> reports = scoutReportRepository.findByPlayerId(playerId);
        assertFalse(reports.isEmpty());
        ScoutReport r = reports.get(0);
        Map<String, Object> data = JsonUtil.parseObjMap(r.getData());
        assertEquals("marchplayer", data.get("attackerName"));
        assertEquals(0, data.get("myLost"));
        assertEquals(928, data.get("myScouts"));
        assertEquals(0, data.get("enemyScouts"));
        assertEquals(true, data.get("showCityInfo"));
        assertEquals("overwhelming_victory", data.get("result"));
    }

    @Test
    @DisplayName("侦查任务: 侦查城市且敌方无侦察机时零损失获得完整情报")
    void testScoutNpcCityZeroEnemyScouts() {
        NpcCity npc = new NpcCity();
        npc.setWorldId(worldId);
        npc.setName("斯德哥尔摩");
        npc.setX(25);
        npc.setY(25);
        npc.setLevel(5);
        npc.setArmy(JsonUtil.toJson(Map.of("infantry", 100, "heavy_tank", 50)));
        npc.setForts(JsonUtil.toJson(Map.of("bunker", 20)));
        npc.setResources(JsonUtil.toJson(Map.of("food", 5000, "steel", 3000)));
        npc.setDefeated(false);
        npc = npcCityRepository.save(npc);

        long now = System.currentTimeMillis();
        createMarch(playerId, "npc", String.valueOf(npc.getId()),
                "斯德哥尔摩", 10, 10, 25, 25,
                Map.of("scout", 928), "scout",
                now - 60000, now - 1000, false, false);

        marchService.processMarches(playerId, now);

        java.util.List<March> marches = marchRepository.findByPlayerId(playerId);
        assertEquals(1, marches.size());
        March returning = marches.get(0);
        assertTrue(returning.getReturning());
        Map<String, Integer> returningArmy = JsonUtil.parseIntMap(returning.getArmy());
        assertEquals(928, returningArmy.get("scout"));

        java.util.List<ScoutReport> reports = scoutReportRepository.findByPlayerId(playerId);
        assertFalse(reports.isEmpty());
        ScoutReport r = reports.get(0);
        Map<String, Object> data = JsonUtil.parseObjMap(r.getData());
        assertEquals(0, data.get("myLost"));
        assertEquals(928, data.get("myScouts"));
        assertEquals(0, data.get("enemyScouts"));
        assertEquals(true, data.get("showCityInfo"));
        assertEquals("overwhelming_victory", data.get("result"));
    }

    @Test
    @DisplayName("守方兵力持久化: 攻打流寇后守方战损应即时保存至数据库")
    void testBanditCasualtiesPersisted() {
        // 创建流寇: 50步兵
        Bandit bandit = new Bandit();
        bandit.setWorldId(worldId);
        bandit.setX(18);
        bandit.setY(18);
        bandit.setLevel(1);
        bandit.setName("流寇小队");
        bandit.setArmy(JsonUtil.toJson(Map.of("infantry", 50)));
        bandit.setDefeated(false);
        bandit = banditRepository.save(bandit);

        createArmyUnit(playerId, "infantry", 100);

        long now = System.currentTimeMillis();
        // 攻方派遣100步兵进攻流寇
        createMarch(playerId, "bandit", String.valueOf(bandit.getId()),
                bandit.getName(), 10, 10, 18, 18,
                Map.of("infantry", 100), "conquer",
                now - 60000, now - 1000, false, false);

        marchService.processMarches(playerId, now);

        // 战斗获胜后，流寇应被标记为 defeated=true，兵力清空
        Bandit updatedBandit = banditRepository.findById(bandit.getId()).orElseThrow();
        assertTrue(updatedBandit.getDefeated(), "流寇应被击败");
        Map<String, Integer> banditArmy = JsonUtil.parseIntMap(updatedBandit.getArmy());
        assertTrue(banditArmy.isEmpty() || banditArmy.values().stream().allMatch(v -> v == 0),
                "被全歼流寇兵力应清空");
    }

    @Test
    @DisplayName("击败玩家城守军后保留城权，攻守双方各收到一份未读战报")
    void conquestReportBelongsToOriginalDefender() {
        Long defenderId = createTestPlayer("report-defender", 30).getId();
        createArmyUnit(defenderId, "infantry", 1);
        PlayerCity city = createTestCity("防守城", defenderId, 20, 20);
        city.setArmy(JsonUtil.toJson(Map.of("infantry", 1)));
        city.setResources(JsonUtil.toJson(Map.of("food", 100)));
        playerCityRepository.save(city);
        long now = System.currentTimeMillis();
        createMarch(playerId, "player", String.valueOf(city.getId()), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 1000), "conquer",
                now - 60000, now - 1, false, false);

        marchService.processMarches(playerId, now);

        assertEquals(defenderId, playerCityRepository.findById(city.getId()).orElseThrow().getOwnerId());
        assertEquals(1L, scoutReportRepository.countUnreadByPlayerId(playerId));
        assertEquals(1L, scoutReportRepository.countUnreadByPlayerId(defenderId));
        assertEquals(1L, gameStateService.getGameState(defenderId).get("unreadReportCount"));
        assertEquals("battle", scoutReportRepository.findByPlayerId(defenderId).get(0).getType());
        for (Long recipient : java.util.List.of(playerId, defenderId)) {
            Map<String, Object> report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(recipient).get(0).getData());
            assertEquals("conquer", report.get("action"));
            assertEquals("marchplayer", report.get("attackerName"));
            assertEquals("20,20", report.get("toCoord"));
        }
    }

    @Test
    @DisplayName("防守获胜时攻守双方也各收到一份未读战报")
    void failedAttackNotifiesBothPlayers() {
        Long defenderId = createTestPlayer("strong-defender", 30).getId();
        createArmyUnit(defenderId, "infantry", 1000);
        PlayerCity city = createTestCity("坚固防守城", defenderId, 20, 20);
        city.setArmy(JsonUtil.toJson(Map.of("infantry", 1000)));
        city.setResources(JsonUtil.toJson(Map.of("food", 100)));
        playerCityRepository.save(city);
        long now = System.currentTimeMillis();
        createMarch(playerId, "player", String.valueOf(city.getId()), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 1), "plunder",
                now - 60000, now - 1, false, false);

        marchService.processMarches(playerId, now);

        assertEquals(defenderId, city.getOwnerId());
        assertEquals(false, JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData()).get("win"));
        assertEquals("plunder", JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData()).get("action"));
        assertEquals(1L, scoutReportRepository.countUnreadByPlayerId(playerId));
        assertEquals(1L, scoutReportRepository.countUnreadByPlayerId(defenderId));
    }

    @Test
    @DisplayName("来袭结算只产生一份战报，重复结算不增加未读数")
    void incomingBattleCreatesOnlyOneUnreadReport() {
        long now = System.currentTimeMillis();
        IncomingMarch incoming = new IncomingMarch();
        incoming.setTargetPlayerId(playerId);
        incoming.setFromName("来袭敌军");
        incoming.setFromX(20);
        incoming.setFromY(20);
        incoming.setArmy(JsonUtil.toJson(Map.of("infantry", 10)));
        incoming.setArriveAt(now - 1);
        incoming.setAction("plunder");
        incomingMarchRepository.save(incoming);

        marchService.processIncoming(playerId, now);
        marchService.processIncoming(playerId, now);

        assertEquals(1L, scoutReportRepository.countUnreadByPlayerId(playerId));
        assertEquals(1, scoutReportRepository.findByPlayerId(playerId).size());
        assertEquals("battle", scoutReportRepository.findByPlayerId(playerId).get(0).getType());
    }

    private PlayerCity createTestCity(String name, Long ownerId, int x, int y) {
        PlayerCity c = new PlayerCity();
        c.setWorldId(worldId);
        c.setName(name);
        c.setOwnerId(ownerId);
        c.setX(x);
        c.setY(y);
        c.setLevel(5);
        return c;
    }

    @Test
    @DisplayName("分层侦查阶梯: 侦查科技 Lv.0 时仅获取基础资源与模糊守军")
    void testScoutReportAtReconLevel0() {
        Long defPlayerId = createTestPlayer("defender_lv0", 30).getId();
        PlayerCity defCity = createTestCity("防守城池0", defPlayerId, 20, 20);
        defCity.setArmy(JsonUtil.toJson(Map.of("infantry", 200, "heavy_tank", 50)));
        defCity.setForts(JsonUtil.toJson(Map.of("bunker", 30)));
        defCity.setResources(JsonUtil.toJson(Map.of("food", 10000, "steel", 8000)));
        playerCityRepository.save(defCity);
        createBuilding(defPlayerId, "command", 5);
        createBuilding(defPlayerId, "depot", 3);
        createTechnology(defPlayerId, "cmd_attack", 3);
        createOfficer(defPlayerId, "commander", 60, 40, 50);

        long now = System.currentTimeMillis();
        createMarch(playerId, "player", String.valueOf(defCity.getId()),
                "防守城池0", 10, 10, 20, 20,
                Map.of("scout", 50), "scout",
                now - 60000, now - 1000, false, false);

        marchService.processMarches(playerId, now);

        java.util.List<ScoutReport> reports = scoutReportRepository.findByPlayerId(playerId);
        assertFalse(reports.isEmpty());
        Map<String, Object> data = JsonUtil.parseObjMap(reports.get(0).getData());

        assertEquals(0, data.get("reconLevel"));
        assertEquals("目视粗探", data.get("tierName"));
        assertTrue((Boolean) data.get("showCityInfo"));
        assertNotNull(data.get("resources"), "基础资源应展示");
        assertNotNull(data.get("armyVague"), "Lv.0应展示模糊守军概括");
        assertNull(data.get("army"), "Lv.0不应泄露精确守军兵力");
        assertNull(data.get("forts"), "Lv.0不应泄露城防设施");
        assertNull(data.get("buildings"), "Lv.0不应泄露建筑等级");
        assertNull(data.get("techs"), "Lv.0不应泄露科研科技");
        assertNull(data.get("officers"), "Lv.0不应泄露军官列表");
    }

    @Test
    @DisplayName("分层侦查阶梯: 侦查科技 Lv.2 时解锁外围城防、精确守军与统帅")
    void testScoutReportAtReconLevel2() {
        createTechnology(playerId, "recon_level", 2);

        Long defPlayerId = createTestPlayer("defender_lv2", 30).getId();
        PlayerCity defCity = createTestCity("防守城池2", defPlayerId, 22, 22);
        defCity.setArmy(JsonUtil.toJson(Map.of("infantry", 200, "heavy_tank", 50)));
        defCity.setForts(JsonUtil.toJson(Map.of("bunker", 30)));
        defCity.setResources(JsonUtil.toJson(Map.of("food", 10000, "steel", 8000)));
        playerCityRepository.save(defCity);
        createBuilding(defPlayerId, "command", 5);
        createBuilding(defPlayerId, "depot", 3);
        createTechnology(defPlayerId, "cmd_attack", 3);
        Officer off = createOfficer(defPlayerId, "commander", 60, 40, 50);
        off.setName("隆美尔");
        officerRepository.save(off);

        long now = System.currentTimeMillis();
        createMarch(playerId, "player", String.valueOf(defCity.getId()),
                "防守城池2", 10, 10, 22, 22,
                Map.of("scout", 50), "scout",
                now - 60000, now - 1000, false, false);

        marchService.processMarches(playerId, now);

        java.util.List<ScoutReport> reports = scoutReportRepository.findByPlayerId(playerId);
        assertFalse(reports.isEmpty());
        Map<String, Object> data = JsonUtil.parseObjMap(reports.get(0).getData());

        assertEquals(2, data.get("reconLevel"));
        assertEquals("战术全貌", data.get("tierName"));
        assertNotNull(data.get("resources"));
        assertNotNull(data.get("forts"), "Lv.2应解锁城防设施");
        assertNotNull(data.get("army"), "Lv.2应解锁精确守军");
        assertNotNull(data.get("commander"), "Lv.2应解锁统帅");
        assertNull(data.get("buildings"), "Lv.2不应解锁建筑");
        assertNull(data.get("techs"), "Lv.2不应解锁科技");
        assertNull(data.get("officers"), "Lv.2不应解锁军官明细列表");
    }

    @Test
    @DisplayName("分层侦查阶梯: 侦查科技 Lv.5 时解锁全维绝密情报（建筑、科技、可掠夺、将领档案、战力评分）")
    void testScoutReportAtReconLevel5() {
        createTechnology(playerId, "recon_level", 5);

        Long defPlayerId = createTestPlayer("defender_lv5", 30).getId();
        PlayerCity defCity = createTestCity("防守城池5", defPlayerId, 24, 24);
        defCity.setArmy(JsonUtil.toJson(Map.of("infantry", 500, "heavy_tank", 100)));
        defCity.setForts(JsonUtil.toJson(Map.of("bunker", 50, "howitzer", 20)));
        defCity.setResources(JsonUtil.toJson(Map.of("food", 20000, "steel", 15000)));
        playerCityRepository.save(defCity);
        createBuilding(defPlayerId, "command", 8);
        createBuilding(defPlayerId, "depot", 4);
        createTechnology(defPlayerId, "cmd_attack", 6);
        Officer cmd = createOfficer(defPlayerId, "commander", 85, 60, 70);
        cmd.setName("古德里安");
        officerRepository.save(cmd);

        long now = System.currentTimeMillis();
        createMarch(playerId, "player", String.valueOf(defCity.getId()),
                "防守城池5", 10, 10, 24, 24,
                Map.of("scout", 100), "scout",
                now - 60000, now - 1000, false, false);

        marchService.processMarches(playerId, now);

        java.util.List<ScoutReport> reports = scoutReportRepository.findByPlayerId(playerId);
        assertFalse(reports.isEmpty());
        Map<String, Object> data = JsonUtil.parseObjMap(reports.get(0).getData());

        assertEquals(5, data.get("reconLevel"));
        assertEquals("全维绝密", data.get("tierName"));
        assertNotNull(data.get("resources"));
        assertNotNull(data.get("forts"));
        assertNotNull(data.get("army"));
        assertNotNull(data.get("commander"));
        assertNotNull(data.get("buildings"), "Lv.5应包含建筑等级");
        assertNotNull(data.get("techs"), "Lv.5应包含科技等级");
        assertNotNull(data.get("plunderable"), "Lv.5应包含可掠夺资源");
        assertNotNull(data.get("officers"), "Lv.5应包含将领全维档案");
        assertNotNull(data.get("defensePower"), "Lv.5应包含防守战力评分");
        assertNotNull(data.get("threatLevel"), "Lv.5应包含威胁评估等级");
    }

    @Test
    @DisplayName("旧版防守科技不会降低侦察等级，情报按出征方等级开放")
    void testScoutReportIgnoresRetiredDefenderTechnology() {
        createTechnology(playerId, "recon_level", 4);

        Long defPlayerId = createTestPlayer("defender_stealth", 30).getId();
        PlayerCity defCity = createTestCity("隐蔽城池", defPlayerId, 26, 26);
        defCity.setArmy(JsonUtil.toJson(Map.of("infantry", 300)));
        defCity.setForts(JsonUtil.toJson(Map.of("bunker", 10)));
        defCity.setResources(JsonUtil.toJson(Map.of("food", 5000)));
        playerCityRepository.save(defCity);
        createBuilding(defPlayerId, "command", 6);
        createTechnology(defPlayerId, "cmd_attack", 4);
        // 兼容尚未清理的旧版数据：满级旧科技也不能影响新侦查。
        createTechnology(defPlayerId, "recon_stealth", 5);

        long now = System.currentTimeMillis();
        createMarch(playerId, "player", String.valueOf(defCity.getId()),
                "隐蔽城池", 10, 10, 26, 26,
                Map.of("scout", 50), "scout",
                now - 60000, now - 1000, false, false);

        marchService.processMarches(playerId, now);

        java.util.List<ScoutReport> reports = scoutReportRepository.findByPlayerId(playerId);
        assertFalse(reports.isEmpty());
        Map<String, Object> data = JsonUtil.parseObjMap(reports.get(0).getData());

        assertEquals(4, data.get("reconLevel"), "我方侦查科技应为4");
        assertFalse(data.containsKey("defenderStealth"));
        assertFalse(data.containsKey("effectiveReconLevel"));
        assertEquals("电磁与科研", data.get("tierName"));
        assertNotNull(data.get("forts"));
        assertNotNull(data.get("army"));
        assertNotNull(data.get("buildings"), "Lv.4应显示建筑");
        assertNotNull(data.get("techs"), "Lv.4应显示科技");
        assertFalse(((Map<?, ?>) data.get("techs")).containsKey("recon_stealth"));
        assertNotNull(data.get("plunderable"));
        assertTrue((Boolean) data.get("showCityInfo"));
    }
}
