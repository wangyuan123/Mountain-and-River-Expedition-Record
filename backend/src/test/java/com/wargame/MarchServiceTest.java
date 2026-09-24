package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.dto.BattleResult;
import com.wargame.model.entity.*;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.service.WorldViewService;
import com.wargame.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MarchService 单元测试")
class MarchServiceTest extends BaseServiceTest {

    @Autowired private BattleSessionRepository battleSessionRepository;
    @Autowired private WorldViewService worldViewService;

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
    @DisplayName("创建出征: 已任命市长或指挥官的军官不可随军出征")
    void testCreateDispatchRejectsAppointedOfficer() {
        createArmyUnit(playerId, "infantry", 100);
        Officer mayor = createOfficer(playerId, "mayor", 30, 20, 10);
        Officer commander = createOfficer(playerId, "commander", 40, 20, 10);

        IllegalArgumentException mayorError = assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(
                playerId, new DispatchRequest("wild", wildTile.getId(), "conquer", Map.of("infantry", 50), mayor.getId(), null)));
        assertEquals("请先解除市长或指挥官任命再出征", mayorError.getMessage());

        IllegalArgumentException commanderError = assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(
                playerId, new DispatchRequest("wild", wildTile.getId(), "conquer", Map.of("infantry", 50), commander.getId(), null)));
        assertEquals("请先解除市长或指挥官任命再出征", commanderError.getMessage());
    }

    @Test
    @DisplayName("创建出征: 零粮食库存时仅按距离预扣油耗")
    void testCreateDispatchDeductsMarchFuelWithoutFoodRequirement() {
        createArmyUnit(playerId, "infantry", 10);
        createArmyUnit(playerId, "special", 2);
        giveResources(playerId, 0, 1000, 1000, 500, 500);

        March march = marchService.createDispatch(playerId, new DispatchRequest(
                "wild", wildTile.getId(), "conquer",
                Map.of("infantry", 10, "special", 2), null, null
        ));

        // 单程 10 格；普通出征预扣往返油耗，但零粮食不能阻止出征。
        Resources resources = getResources(playerId);
        assertEquals(10, march.getDistance());
        assertEquals(0, resources.getFood(), "出征不应消耗或要求粮食");
        assertEquals(999, resources.getOil(), "特种兵装备按 100 格单程、往返两程预扣石油");
    }

    @Test
    @DisplayName("战报: 可持久化超过 TEXT 上限的逐回合战斗日志")
    void battleReportStoresLargeTacticalLog() {
        String largeReportData = "{\"report\":\"" + "x".repeat(70_000) + "\"}";
        ScoutReport report = new ScoutReport();
        report.setPlayerId(playerId);
        report.setTargetName("大型战报测试");
        report.setData(largeReportData);
        report.setType("battle");
        report.setCreatedAt(System.currentTimeMillis());
        report.setReadAt(0L);

        ScoutReport saved = scoutReportRepository.saveAndFlush(report);
        assertEquals(largeReportData, scoutReportRepository.findById(saved.getId()).orElseThrow().getData());
    }

    @Test
    @DisplayName("战术战斗: 未进入指挥时每 15 秒自动执行一回合，运输单位保持待命")
    void tacticalBattleAutoAdvancesAfterRoundTimeout() {
        NpcCity npc = new NpcCity();
        npc.setWorldId(worldId);
        npc.setName("自动回合测试营地");
        npc.setLevel(1);
        npc.setX(20);
        npc.setY(20);
        npc.setArmy(JsonUtil.toJson(Map.of("infantry", 10)));
        npc.setForts("{}");
        npc.setResources("{}");
        npc.setDefeated(false);
        npc = npcCityRepository.save(npc);
        createArmyUnit(playerId, "infantry", 10);
        createArmyUnit(playerId, "truck", 1);

        March march = marchService.createDispatch(playerId, new DispatchRequest(
                "npc", npc.getId(), "conquer", Map.of("infantry", 10, "truck", 1), null, null
        ));
        long arrival = march.getArriveAt();
        marchService.processMarches(playerId, arrival);

        Map<String, Object> initial = marchService.getTacticalBattle(playerId, march.getId());
        assertEquals(0, ((Number) initial.get("round")).intValue());
        assertEquals(arrival + 15_000L, ((Number) initial.get("roundDeadlineAt")).longValue());

        BattleSession session = battleSessionRepository.findByMarchId(march.getId()).orElseThrow();
        marchService.processTimedOutTacticalBattle(session.getId(), arrival + 14_999L);
        assertEquals(0, ((Number) marchService.getTacticalBattle(playerId, march.getId()).get("round")).intValue());

        marchService.processTimedOutTacticalBattle(session.getId(), arrival + 15_000L);
        Map<String, Object> autoResolved = marchService.getTacticalBattle(playerId, march.getId());
        assertEquals(1, ((Number) autoResolved.get("round")).intValue());
        assertEquals(arrival + 30_000L, ((Number) autoResolved.get("roundDeadlineAt")).longValue());
        assertTrue(((Number) ((Map<?, ?>) autoResolved.get("attackerPositions")).get("infantry")).intValue() > 0,
                "可攻击步兵应按默认战术前进");
        assertEquals(0, ((Number) ((Map<?, ?>) autoResolved.get("attackerPositions")).get("truck")).intValue(),
                "卡车属于后勤单位，默认不前进");
    }

    @Test
    @DisplayName("战术战斗: 倒计时提交的全部前进覆盖默认待命兵种")
    void tacticalBattleAcceptsAdvanceAtDeadline() {
        NpcCity npc = new NpcCity();
        npc.setWorldId(worldId);
        npc.setName("批量前进测试营地");
        npc.setLevel(1);
        npc.setX(20);
        npc.setY(20);
        npc.setArmy(JsonUtil.toJson(Map.of("infantry", 10)));
        npc.setForts("{}");
        npc.setResources("{}");
        npc.setDefeated(false);
        npc = npcCityRepository.save(npc);
        for (String unitId : new String[]{"truck", "scout", "transport"}) {
            createArmyUnit(playerId, unitId, 1);
        }
        Map<String, Integer> army = Map.of("truck", 1, "scout", 1, "transport", 1);
        March march = marchService.createDispatch(playerId, new DispatchRequest(
                "npc", npc.getId(), "conquer", army, null, null));
        marchService.processMarches(playerId, march.getArriveAt());
        BattleSession session = battleSessionRepository.findByMarchId(march.getId()).orElseThrow();
        session.setRoundDeadlineAt(System.currentTimeMillis() - 1);
        battleSessionRepository.saveAndFlush(session);

        Map<String, GameDtos.BattleUnitOrderRequest> orders = Map.of(
                "truck", new GameDtos.BattleUnitOrderRequest("ADVANCE", null),
                "scout", new GameDtos.BattleUnitOrderRequest("ADVANCE", null),
                "transport", new GameDtos.BattleUnitOrderRequest("ADVANCE", null));
        Map<String, Object> resolved = marchService.executeTacticalRound(playerId, march.getId(),
                new GameDtos.BattleCommandRequest(orders, session.getRoundNo()));

        assertEquals(1, ((Number) resolved.get("round")).intValue());
        Map<?, ?> positions = (Map<?, ?>) resolved.get("attackerPositions");
        for (String unitId : army.keySet()) {
            assertTrue(((Number) positions.get(unitId)).intValue() > 0, unitId + " 未执行前进");
        }
    }

    @Test
    @DisplayName("战术战斗: 历史会话缺失截止时间时进入指挥应补发 15 秒倒计时")
    void tacticalBattleRestoresMissingRoundDeadline() {
        NpcCity npc = new NpcCity();
        npc.setWorldId(worldId);
        npc.setName("历史战场测试营地");
        npc.setLevel(1);
        npc.setX(20);
        npc.setY(20);
        npc.setArmy(JsonUtil.toJson(Map.of("infantry", 10)));
        npc.setForts("{}");
        npc.setResources("{}");
        npc.setDefeated(false);
        npc = npcCityRepository.save(npc);
        createArmyUnit(playerId, "infantry", 10);

        March march = marchService.createDispatch(playerId, new DispatchRequest(
                "npc", npc.getId(), "conquer", Map.of("infantry", 10), null, null
        ));
        marchService.processMarches(playerId, march.getArriveAt());
        BattleSession session = battleSessionRepository.findByMarchId(march.getId()).orElseThrow();
        session.setRoundDeadlineAt(0L);
        battleSessionRepository.saveAndFlush(session);

        long before = System.currentTimeMillis();
        Map<String, Object> recovered = marchService.getTacticalBattle(playerId, march.getId());

        assertTrue(((Number) recovered.get("roundDeadlineAt")).longValue() >= before + 14_000L,
                "历史战斗进入指挥后必须重新获得完整的 15 秒决策窗口");
    }

    @Test
    @DisplayName("战术战斗: 独立超时调度不依赖玩家经济 Tick")
    void timedTacticalBattleResolutionDoesNotRequirePlayerTick() {
        NpcCity npc = new NpcCity();
        npc.setWorldId(worldId);
        npc.setName("独立调度测试营地");
        npc.setLevel(1);
        npc.setX(20);
        npc.setY(20);
        npc.setArmy(JsonUtil.toJson(Map.of("infantry", 10)));
        npc.setForts("{}");
        npc.setResources("{}");
        npc.setDefeated(false);
        npc = npcCityRepository.save(npc);
        createArmyUnit(playerId, "infantry", 10);

        March march = marchService.createDispatch(playerId, new DispatchRequest(
                "npc", npc.getId(), "conquer", Map.of("infantry", 10), null, null
        ));
        marchService.processMarches(playerId, march.getArriveAt());
        BattleSession session = battleSessionRepository.findByMarchId(march.getId()).orElseThrow();
        long deadline = System.currentTimeMillis() - 1L;
        session.setRoundDeadlineAt(deadline);
        battleSessionRepository.saveAndFlush(session);

        marchService.processTimedOutTacticalBattle(session.getId(), deadline);

        assertEquals(1, battleSessionRepository.findById(session.getId()).orElseThrow().getRoundNo(),
                "战术超时调度应直接推进回合，而不等待玩家经济 Tick");
    }

    @Test
    @DisplayName("战术战斗: 首次 Tick 缺少时间戳仍必须处理已到达行军")
    void firstTickWithoutTimestampStillProcessesArrivedMarch() {
        NpcCity npc = new NpcCity();
        npc.setWorldId(worldId);
        npc.setName("首 Tick 战场测试营地");
        npc.setLevel(1);
        npc.setX(20);
        npc.setY(20);
        npc.setArmy(JsonUtil.toJson(Map.of("infantry", 10)));
        npc.setForts("{}");
        npc.setResources("{}");
        npc.setDefeated(false);
        npc = npcCityRepository.save(npc);
        createArmyUnit(playerId, "infantry", 10);

        March march = marchService.createDispatch(playerId, new DispatchRequest(
                "npc", npc.getId(), "conquer", Map.of("infantry", 10), null, null
        ));
        march.setArriveAt(System.currentTimeMillis() - 1_000L);
        marchRepository.saveAndFlush(march);
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setLastTick(null);
        playerRepository.saveAndFlush(player);

        tickService.tick(playerId);

        assertTrue(marchRepository.findById(march.getId()).orElseThrow().getBattleId() != null,
                "首次 Tick 也必须创建已到达行军的战斗会话");
        assertNotNull(playerRepository.findById(playerId).orElseThrow().getLastTick(),
                "首次 Tick 应写入时间戳，后续调度才不会反复停滞");
    }

    @Test
    @DisplayName("战术战斗: 双方存活至第 30 回合时必须结算并返程")
    void tacticalBattleSettlesAtThirtiethRound() {
        NpcCity npc = new NpcCity();
        npc.setWorldId(worldId);
        npc.setName("回合上限测试营地");
        npc.setLevel(1);
        npc.setX(20);
        npc.setY(20);
        npc.setArmy(JsonUtil.toJson(Map.of("truck", 1)));
        npc.setForts("{}");
        npc.setResources("{}");
        npc.setDefeated(false);
        npc = npcCityRepository.save(npc);
        createArmyUnit(playerId, "truck", 1);

        March march = marchService.createDispatch(playerId, new DispatchRequest(
                "npc", npc.getId(), "plunder", Map.of("truck", 1), null, null
        ));
        long arrival = march.getArriveAt();
        marchService.processMarches(playerId, arrival);
        Long battleSessionId = battleSessionRepository.findByMarchId(march.getId()).orElseThrow().getId();
        for (int round = 1; round <= 30; round++) {
            marchService.processTimedOutTacticalBattle(battleSessionId, arrival + round * 15_000L);
        }

        March settled = marchRepository.findById(march.getId()).orElseThrow();
        assertNull(settled.getBattleId(), "第 30 回合结算后必须清除战斗会话关联");
        assertTrue(settled.getReturning(), "第 30 回合未分胜负时，幸存部队必须返程");
        assertFalse(scoutReportRepository.findByPlayerId(playerId).isEmpty(), "第 30 回合结算后必须生成战报");
    }

    @Test
    @DisplayName("战术战斗: 已推进回合的旧指令只返回最新战场")
    void tacticalBattleRejectsStaleRoundCommand() {
        NpcCity npc = new NpcCity();
        npc.setWorldId(worldId);
        npc.setName("旧指令测试营地");
        npc.setLevel(1);
        npc.setX(20);
        npc.setY(20);
        npc.setArmy(JsonUtil.toJson(Map.of("infantry", 20)));
        npc.setForts("{}");
        npc.setResources("{}");
        npc.setDefeated(false);
        npc = npcCityRepository.save(npc);
        createArmyUnit(playerId, "infantry", 20);

        March march = marchService.createDispatch(playerId, new DispatchRequest(
                "npc", npc.getId(), "conquer", Map.of("infantry", 20), null, null
        ));
        marchService.processMarches(playerId, march.getArriveAt());
        Map<String, Object> initial = marchService.getTacticalBattle(playerId, march.getId());

        Map<String, Object> resolved = marchService.executeTacticalRound(playerId, march.getId(),
                new GameDtos.BattleCommandRequest(Map.of(), ((Number) initial.get("round")).intValue()));
        Map<String, Object> staleResponse = marchService.executeTacticalRound(playerId, march.getId(),
                new GameDtos.BattleCommandRequest(Map.of(), ((Number) initial.get("round")).intValue()));

        assertEquals(1, ((Number) resolved.get("round")).intValue());
        assertEquals(1, ((Number) staleResponse.get("round")).intValue());
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
    @DisplayName("创建出征: 编队超过带兵上限时应拦截")
    void testDispatchArmyExceedsCap() {
        createArmyUnit(playerId, "infantry", 100_000);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> marchService.createDispatch(playerId, new DispatchRequest(
                        "wild", wildTile.getId(), "conquer", Map.of("infantry", 100_000), null, null))
        );

        assertTrue(ex.getMessage().startsWith("出征兵力超过带兵上限 "));
        assertTrue(armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount() == 100_000,
                "校验失败时不应扣除兵力");
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
        settleWithDefaultTactics(marchRepository.findByPlayerId(playerId).get(0), now);

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
        settleWithDefaultTactics(marchRepository.findByPlayerId(playerId).get(0), now);

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

        settleWithDefaultTactics(marchRepository.findByPlayerId(playerId).get(0), now);

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
        March march = createMarch(playerId, "bandit", String.valueOf(bandit.getId()),
                bandit.getName(), 10, 10, 18, 18,
                Map.of("infantry", 100), "conquer",
                now - 60000, now - 1000, false, false);

        settleWithDefaultTactics(march, now);

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
        March march = createMarch(playerId, "player", String.valueOf(city.getId()), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 1000), "conquer",
                now - 60000, now - 1, false, false);

        settleWithDefaultTactics(march, now);

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
            assertNotNull(report.get("prestigeChange"));
            assertNotNull(report.get("prestigeAfter"));
            assertNotNull(report.get("moraleChange"));
            assertNotNull(report.get("moraleAfter"));
            assertNotNull(report.get("losses"));
            assertNotNull(report.get("recoveryPercent"));
            assertNotNull(report.get("recoveredCount"));
        }
    }

    @Test
    @DisplayName("战术战斗: 被攻击城市的所有者可从来袭行军进入并指挥守军")
    void defenderCanCommandArrivedPlayerBattle() {
        Long defenderId = createTestPlayer("tactical-defender", 30).getId();
        createArmyUnit(defenderId, "infantry", 20);
        PlayerCity city = createTestCity("战术防守城", defenderId, 20, 20);
        city.setArmy(JsonUtil.toJson(Map.of("infantry", 20)));
        city.setForts("{}");
        city.setResources("{}");
        city = playerCityRepository.save(city);

        long now = System.currentTimeMillis();
        Player attacker = playerRepository.findById(playerId).orElseThrow();
        attacker.setWarAgainstId(defenderId);
        attacker.setWarAt(now - 1L);
        attacker.setWarEndAt(now + 60_000L);
        playerRepository.save(attacker);
        March march = createMarch(playerId, "player", String.valueOf(city.getId()), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 10), "plunder",
                now - 60_000L, now - 1L, false, false);
        marchService.processMarches(playerId, now);

        Map<String, Object> defenderView = marchService.getTacticalBattle(defenderId, march.getId());
        assertEquals("defender", defenderView.get("side"));
        assertEquals(20, ((Number) ((Map<?, ?>) defenderView.get("attackerArmy")).get("infantry")).intValue(),
                "防守视图中的我军应是守城部队");

        Long outsiderId = createTestPlayer("tactical-outsider", 30).getId();
        IllegalArgumentException forbidden = assertThrows(IllegalArgumentException.class,
                () -> marchService.getTacticalBattle(outsiderId, march.getId()));
        assertEquals("无权查看该战斗", forbidden.getMessage());

        Map<String, Object> afterCommand = marchService.executeTacticalRound(defenderId, march.getId(),
                new GameDtos.BattleCommandRequest(Map.of(
                        "infantry", new GameDtos.BattleUnitOrderRequest("HOLD", null)), 0));
        assertEquals(1, ((Number) afterCommand.get("round")).intValue(), "防守方指令应结算一个回合");
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
        March march = createMarch(playerId, "player", String.valueOf(city.getId()), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 1), "plunder",
                now - 60000, now - 1, false, false);

        settleWithDefaultTactics(march, now);

        assertEquals(defenderId, city.getOwnerId());
        assertEquals(false, JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData()).get("win"));
        assertEquals("plunder", JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData()).get("action"));
        assertEquals(1L, scoutReportRepository.countUnreadByPlayerId(playerId));
        assertEquals(1L, scoutReportRepository.countUnreadByPlayerId(defenderId));
    }

    @Test
    @DisplayName("战报按接收者视角记录胜负并保持攻守将领归属")
    void battleReportUsesRecipientPerspective() {
        Long defenderId = createTestPlayer("report-perspective-defender", 30).getId();
        Officer attackerCommander = createOfficer(playerId, "march", 80, 60, 50);
        attackerCommander.setName("朱可夫");
        attackerCommander.setSkills(JsonUtil.toJson(java.util.List.of(
                Map.of("id", "frenzy", "lv", 5),
                Map.of("id", "pierce", "lv", 5)
        )));
        attackerCommander = officerRepository.save(attackerCommander);
        March march = createMarch(playerId, "player", "999", "防守主城",
                10, 10, 20, 20, Map.of("infantry", 1), "plunder",
                System.currentTimeMillis() - 60_000L, System.currentTimeMillis() - 1L, false, false);
        BattleResult result = new BattleResult(false,
                Map.of(), Map.of("infantry", 990),
                Map.of("infantry", 1), Map.of("infantry", 1000),
                Map.of(), 100, "-- 第1回合 --", false);

        Object marchServiceTarget = AopTestUtils.getTargetObject(marchService);
        ReflectionTestUtils.invokeMethod(marchServiceTarget, "pushBattleReport",
                playerId, result, march, attackerCommander, null);
        ReflectionTestUtils.invokeMethod(marchServiceTarget, "pushBattleReport",
                defenderId, result, march, attackerCommander, null);

        Map<String, Object> attackerReport = JsonUtil.parseObjMap(
                scoutReportRepository.findByPlayerId(playerId).get(0).getData());
        Map<String, Object> defenderReport = JsonUtil.parseObjMap(
                scoutReportRepository.findByPlayerId(defenderId).get(0).getData());
        assertEquals("attacker", attackerReport.get("perspective"));
        assertEquals(false, attackerReport.get("attackerWin"));
        assertEquals(false, attackerReport.get("win"));
        assertEquals("defender", defenderReport.get("perspective"));
        assertEquals(false, defenderReport.get("attackerWin"));
        assertEquals(true, defenderReport.get("win"));
        assertTrue(String.valueOf(defenderReport.get("subject")).startsWith("掠夺防守胜利"));
        assertEquals("朱可夫", ((Map<?, ?>) ((Map<?, ?>) defenderReport.get("commanders")).get("attacker")).get("name"));
        assertNull(((Map<?, ?>) defenderReport.get("commanders")).get("defender"));
        java.util.List<?> skills = (java.util.List<?>) ((Map<?, ?>) ((Map<?, ?>) defenderReport.get("commanders")).get("attacker")).get("skills");
        assertEquals("攻击力额外+50%，第1、4、7…回合触发", ((Map<?, ?>) skills.get(0)).get("description"));
        assertEquals("无视敌方防御30%", ((Map<?, ?>) skills.get(1)).get("description"));
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

    /** 模拟攻击方不打开指挥界面时，服务端以 15 秒为间隔结算默认战术回合。 */
    private void settleWithDefaultTactics(March march, long roundTime) {
        marchService.processMarches(playerId, roundTime);
        for (int round = 0; round < 30 && marchRepository.findById(march.getId()).isPresent(); round++) {
            March activeMarch = marchRepository.findById(march.getId()).orElseThrow();
            if (activeMarch.getBattleId() == null) break;
            roundTime += 15_000L;
            marchService.processMarches(playerId, roundTime);
        }
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
    @DisplayName("同一玩家城的多支攻击行军按到达顺序逐场交战")
    void playerCityBattlesQueueUntilPreviousBattleEnds() {
        Long defenderId = createTestPlayer("queued-defender", 30).getId();
        createArmyUnit(defenderId, "infantry", 20);
        PlayerCity city = playerCityRepository.save(createTestCity("排队防守城", defenderId, 20, 20));
        long arrival = System.currentTimeMillis() - 1_000L;
        Player attacker = playerRepository.findById(playerId).orElseThrow();
        attacker.setWarAgainstId(defenderId);
        attacker.setWarAt(arrival - 60_000L);
        attacker.setWarEndAt(arrival + 600_000L);
        playerRepository.save(attacker);

        March first = createMarch(playerId, "player", String.valueOf(city.getId()), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 10), "plunder",
                arrival - 60_000L, arrival - 1L, false, false);
        March second = createMarch(playerId, "player", String.valueOf(city.getId()), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 10), "plunder",
                arrival - 60_000L, arrival, false, false);

        marchService.processMarches(playerId, arrival);
        assertNotNull(marchRepository.findById(first.getId()).orElseThrow().getBattleId());
        assertNull(marchRepository.findById(second.getId()).orElseThrow().getBattleId());
        assertEquals(1L, battleSessionRepository.count());
        assertEquals(true, worldViewService.toMarchMap(second, arrival).get("waitingForBattle"));
        Map<String, Object> incoming = worldViewService.getIncoming(playerRepository.findById(defenderId).orElseThrow())
                .stream().filter(info -> second.getId().equals(info.get("marchId"))).findFirst().orElseThrow();
        assertEquals(true, incoming.get("waitingForBattle"));
        IllegalArgumentException waiting = assertThrows(IllegalArgumentException.class,
                () -> marchService.getTacticalBattle(playerId, second.getId()));
        assertEquals("目标正在交战，部队已到达并等待前一场战斗结束", waiting.getMessage());

        Long sessionId = marchRepository.findById(first.getId()).orElseThrow().getBattleId();
        long roundTime = arrival;
        for (int round = 0; round < 30 && battleSessionRepository.existsById(sessionId); round++) {
            roundTime += 15_000L;
            marchService.processTimedOutTacticalBattle(sessionId, roundTime);
        }
        assertFalse(battleSessionRepository.existsById(sessionId));
        marchService.processMarches(playerId, roundTime);
        assertNotNull(marchRepository.findById(second.getId()).orElseThrow().getBattleId());
        assertEquals(1L, battleSessionRepository.count());
    }

    @Test
    @DisplayName("同一防守玩家的不同城市可同时进入战斗")
    void differentPlayerCitiesCanBattleAtTheSameTime() {
        Long defenderId = createTestPlayer("two-cities-defender", 30).getId();
        PlayerCity firstCity = playerCityRepository.save(createTestCity("第一城", defenderId, 20, 20));
        PlayerCity secondCity = playerCityRepository.save(createTestCity("第二城", defenderId, 25, 25));
        long arrival = System.currentTimeMillis() - 1_000L;
        Player attacker = playerRepository.findById(playerId).orElseThrow();
        attacker.setWarAgainstId(defenderId);
        attacker.setWarAt(arrival - 60_000L);
        attacker.setWarEndAt(arrival + 600_000L);
        playerRepository.save(attacker);
        March first = createMarch(playerId, "player", String.valueOf(firstCity.getId()), firstCity.getName(),
                10, 10, 20, 20, Map.of("infantry", 10), "plunder",
                arrival - 60_000L, arrival, false, false);
        March second = createMarch(playerId, "player", String.valueOf(secondCity.getId()), secondCity.getName(),
                10, 10, 25, 25, Map.of("infantry", 10), "plunder",
                arrival - 60_000L, arrival, false, false);

        marchService.processMarches(playerId, arrival);
        assertNotNull(marchRepository.findById(first.getId()).orElseThrow().getBattleId());
        assertNotNull(marchRepository.findById(second.getId()).orElseThrow().getBattleId());
        assertEquals(2L, battleSessionRepository.count());
        assertNotEquals(true, worldViewService.toMarchMap(first, arrival).get("waitingForBattle"));
        assertNotEquals(true, worldViewService.toMarchMap(second, arrival).get("waitingForBattle"));
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

    @ParameterizedTest
    @CsvSource({"5000, 7000", "7000, 5000"})
    @DisplayName("侦查交火后双方幸存时战平，并按侦查科技展示情报")
    void testScoutDrawStillRevealsIntelligence(int attackingScouts, int defendingScouts) {
        createTechnology(playerId, "recon_level", 2);
        Long defenderId = createTestPlayer("scout_draw_defender_" + attackingScouts, 30).getId();
        PlayerCity city = createTestCity("侦查战平城", defenderId, 27, 27);
        city.setArmy(JsonUtil.toJson(Map.of("scout", defendingScouts, "infantry", 100)));
        city.setResources(JsonUtil.toJson(Map.of("food", 12000)));
        playerCityRepository.save(city);
        createArmyUnit(defenderId, "scout", defendingScouts);
        createArmyUnit(defenderId, "infantry", 100);
        long now = System.currentTimeMillis();
        createMarch(playerId, "player", String.valueOf(city.getId()), city.getName(),
                10, 10, 27, 27, Map.of("scout", attackingScouts), "scout",
                now - 60000, now - 1000, false, false);

        marchService.processMarches(playerId, now);

        Map<String, Object> data = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData());
        assertTrue(attackingScouts > ((Number) data.get("myLost")).intValue());
        assertTrue(defendingScouts > ((Number) data.get("enemyLost")).intValue());
        assertEquals("draw", data.get("result"));
        assertEquals(true, data.get("showCityInfo"));
        assertNotNull(data.get("resources"));
        assertNotNull(data.get("army"), "战平仍应获取当前科技等级允许的守军情报");
        Map<String, Object> defense = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(defenderId).get(0).getData());
        assertEquals("draw", defense.get("result"));
        assertEquals(false, defense.get("intercepted"));
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

    @Test
    @DisplayName("行军速度与科技加成: 多兵种出征按最慢速度计算并应用引擎科技加成")
    void testDispatchMarchSpeedWithTechAndMultiUnits() {
        createArmyUnit(playerId, "scout", 10);
        createArmyUnit(playerId, "infantry", 100);
        createArmyUnit(playerId, "ltank", 20);

        // 1. 纯空军 (scout 基础速度 14)
        DispatchRequest reqScout = new DispatchRequest(
                "wild", wildTile.getId(), "scout",
                Map.of("scout", 5), null, null
        );
        var previewScout = marchService.previewRoute(playerId, reqScout);
        int scoutSeconds = (int) previewScout.get("seconds");

        // 2. 混合部队：侦察机(14) + 步兵(3)，全军必须按最慢步兵速度(3)行军，时间显著更长
        DispatchRequest reqMixed = new DispatchRequest(
                "wild", wildTile.getId(), "conquer",
                Map.of("scout", 5, "infantry", 20), null, null
        );
        var previewMixed = marchService.previewRoute(playerId, reqMixed);
        int mixedSeconds = (int) previewMixed.get("seconds");
        assertTrue(mixedSeconds > scoutSeconds, "混合出征包含步兵时应按最慢步兵速度行军，耗时应显著大于纯侦察机");

        // 3. 科技加成：研究装甲引擎 arm_engine 4级 (+20%)
        createTechnology(playerId, "arm_engine", 4);
        DispatchRequest reqTank = new DispatchRequest(
                "wild", wildTile.getId(), "conquer",
                Map.of("ltank", 10), null, null
        );
        var previewTankWithTech = marchService.previewRoute(playerId, reqTank);
        int dist = (int) previewTankWithTech.get("distance");
        // ltank 基础速度 6, 科技加成 1.20x -> 有效速度 7.2
        // seconds = ceil(dist * 9 / 7.2)
        int expectedSeconds = (int) Math.ceil((double) dist * 9 / 7.2);
        assertEquals(expectedSeconds, (int) previewTankWithTech.get("seconds"), "装甲引擎科技应使坦克出征时间准确缩短");
    }

    @Test
    @DisplayName("占领野地完整流转: 派遣进驻 -> 驻扎成为驻军 -> 原地采集 -> 结算收获 -> 撤军返城 -> 放弃领地")
    void testWildStationGatherHarvestRecallFlow() {
        WildTile tile = createWildTile(worldId, "ironworks", 15, 15, 1, null, 1000);
        tile.setOccupied(true);
        tile.setOccupiedBy(playerId);
        wildTileRepository.save(tile);

        createArmyUnit(playerId, "infantry", 50);
        createArmyUnit(playerId, "truck", 10);

        // 1. 派遣部队进驻
        DispatchRequest stationReq = new DispatchRequest(
                "wild", tile.getId(), "station",
                Map.of("infantry", 20, "truck", 5), null, null
        );
        March march = marchService.createDispatch(playerId, stationReq);
        assertNotNull(march);
        assertEquals("station", march.getAction());

        // 行军抵达
        long arriveAt = march.getArriveAt();
        marchService.processMarches(playerId, arriveAt + 1000);

        // 验证行军已完成，且部队已作为驻军进驻到野地
        assertTrue(marchRepository.findById(march.getId()).isEmpty(), "进驻完成后行军记录应已删除");
        WildTile stationedTile = wildTileRepository.findById(tile.getId()).orElseThrow();
        Map<String, Integer> garrison = com.wargame.util.JsonUtil.parseIntMap(stationedTile.getGarrison());
        assertEquals(20, garrison.get("infantry"), "野地驻军应包含20名步兵");
        assertEquals(5, garrison.get("truck"), "野地驻军应包含5辆卡车");

        // 2. 原地开启资源采集
        Map<String, Object> gatherRes = marchService.startWildGather(playerId, tile.getId());
        assertTrue((boolean) gatherRes.get("success"));
        WildTile gatheringTile = wildTileRepository.findById(tile.getId()).orElseThrow();
        assertTrue(gatheringTile.getGathering(), "野地应处于采集中状态");
        assertEquals("steel", gatheringTile.getGatherRes(), "炼铁厂采集资源应为钢铁");
        assertTrue(gatheringTile.getGatherLoad() > 0, "采集载荷应大于0");

        // 3. 结算收获
        int steelBefore = getResources(playerId).getSteel();
        Map<String, Object> harvestRes = marchService.harvestWild(playerId, tile.getId());
        assertTrue((boolean) harvestRes.get("success"));
        int harvestAmount = (int) harvestRes.get("harvestAmount");
        assertTrue(harvestAmount >= 0);
        WildTile harvestedTile = wildTileRepository.findById(tile.getId()).orElseThrow();
        assertFalse(harvestedTile.getGathering(), "收获后采集中状态应结束");
        assertEquals(harvestAmount, harvestedTile.getMined(), "野地已开采量应累加");

        // 4. 撤回驻军
        int infBefore = armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount();
        Map<String, Object> recallRes = marchService.recallWild(playerId, tile.getId());
        assertTrue((boolean) recallRes.get("success"));
        WildTile recalledTile = wildTileRepository.findById(tile.getId()).orElseThrow();
        assertTrue(recalledTile.getOccupied(), "撤回驻军后野地仍应属于我方占领");
        assertEquals("{}", recalledTile.getGarrison(), "撤军后野地驻军应清空");
        int infAfter = armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount();
        assertEquals(infBefore + 20, infAfter, "撤回后步兵应归还主城军营");

        // 5. 放弃领地
        Map<String, Object> abandonRes = worldService.abandonWild(playerId, tile.getId());
        assertTrue((boolean) abandonRes.get("success"));
        WildTile abandonedTile = wildTileRepository.findById(tile.getId()).orElseThrow();
        assertFalse(abandonedTile.getOccupied(), "放弃后野地不再被占领");
        assertNull(abandonedTile.getOccupiedBy(), "放弃后占领者应为null");
    }
}
