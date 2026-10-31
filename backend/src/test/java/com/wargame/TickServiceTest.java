package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.util.GameClock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TickService 单元测试")
class TickServiceTest extends BaseServiceTest {

    @Test
    void fiveSecondAccrualPreservesLowLevelProductionFoodAndSalaryAcrossReloads() throws Exception {
        long start = System.currentTimeMillis();
        var time = new java.util.concurrent.atomic.AtomicLong(start);
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setLastTick(start); player.setTax(0);
        for (String key : java.util.List.of("farm", "refinery", "oilfield", "raremine")) createBuilding(playerId, key, 1);
        createArmyUnit(playerId, "infantry", 100);
        Officer officer = createOfficer(playerId, "idle", 0, 0, 0); officer.setSalary(100);
        giveResources(playerId, 1000, 1000, 1000, 1000, 1000);
        try (var ignored = GameClock.use(time::get)) {
            for (int i = 0; i < 12; i++) {
                time.addAndGet(5000); tickService.tick(playerId);
            }
        }
        Resources stock = getResources(playerId);
        assertEquals(1000 + 500 / 60.0, stock.getFood() + stock.getFoodAccrual(), 1e-6);
        assertEquals(1010, stock.getSteel());
        assertEquals(1000 + 500 / 60.0, stock.getOil() + stock.getOilAccrual(), 1e-6);
        assertEquals(1000 + 500 / 60.0, stock.getRare() + stock.getRareAccrual(), 1e-6);
        assertEquals(1000 - 100 / 60.0, stock.getGold() + stock.getGoldAccrual(), 1e-6);
    }

    private Long playerId;

    @BeforeEach
    void setUp() {
        Player player = createTestPlayer("tickplayer", 0);
        playerId = player.getId();
    }

    @Test
    @DisplayName("资源建筑产出: 农田/炼钢厂/油田/稀矿厂产出正确数量")
    void testResourceProduction() {
        // Set lastTick to 1 hour ago
        Player player = playerRepository.findById(playerId).orElseThrow();
        long now = System.currentTimeMillis();
        player.setLastTick(now - 3600_000L); // 1 hour ago
        player.setTax(0); // no tax to simplify gold
        playerRepository.save(player);

        // Create resource buildings at level 1
        createBuilding(playerId, "farm", 1);
        createBuilding(playerId, "refinery", 1);
        createBuilding(playerId, "oilfield", 1);
        createBuilding(playerId, "raremine", 1);

        // Set initial resources to 0
        giveResources(playerId, 0, 0, 0, 0, 0);

        // Run tick
        tickService.tick(playerId);

        Resources res = getResources(playerId);
        assertNotNull(res);

        // farm: baseProduce=600, curve=1*(1+0.12*0)=1, produce=floor(600*1)=600
        // food = 0 + 600 * 1 hour * 1.0 (peace) = 600
        // No army, so no food consumption
        assertEquals(600, res.getFood(), "农田1级1小时应产出600粮食");

        // refinery: baseProduce=600, same logic => 600
        assertEquals(600, res.getSteel(), "炼钢厂1级1小时应产出600钢铁");

        // oilfield: baseProduce=500 => 500
        assertEquals(500, res.getOil(), "油田1级1小时应产出500石油");

        // raremine: baseProduce=500 => 500
        assertEquals(500, res.getRare(), "稀矿厂1级1小时应产出500稀矿");
    }

    @Test
    @DisplayName("粮食消耗: 部队按规模消耗粮食")
    void testFoodConsumption() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        long now = System.currentTimeMillis();
        player.setLastTick(now - 3600_000L); // 1 hour ago
        player.setTax(0);
        playerRepository.save(player);

        // Create a farm (produces 600 food/hour)
        createBuilding(playerId, "farm", 1);

        // Create 100 infantry (each consumes 1 food/hour => 100 food/hour)
        createArmyUnit(playerId, "infantry", 100);

        // Set initial food to 200
        giveResources(playerId, 200, 0, 0, 0, 0);

        tickService.tick(playerId);

        Resources res = getResources(playerId);
        assertNotNull(res);

        // food production: 200 + 600 = 800 (after production)
        // food consumption: 100 infantry * 1 food * 1.0 (no log_food tech) * 1 hour = 100
        // food final: 800 - 100 = 700
        assertEquals(700, res.getFood(), "200初始粮+600产出-100消耗应等于700");
    }

    @Test
    @DisplayName("黄金收入: 税收产生黄金")
    void testGoldIncome() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        long now = System.currentTimeMillis();
        player.setLastTick(now - 3600_000L); // 1 hour ago
        player.setTax(30); // 30% tax
        player.setCivilianPopulation(100);
        playerRepository.save(player);

        // 当前民居每级容量 1200：100 平民一小时自然增长 36 人。
        createBuilding(playerId, "house", 1);
        giveResources(playerId, 0, 0, 0, 0, 0);
        tickService.tick(playerId);

        assertEquals(136, playerRepository.findById(playerId).orElseThrow().getCivilianPopulation());
        assertEquals(71, getResources(playerId).getGold(),
                "人口由100连续增至136，平均118人口按30%税率纳税，70.8黄金显示为71");
    }

    @Test
    @DisplayName("人口满额时停止增长，税收以实际平民而非容量为准")
    void testGoldIncomeAtPopulationCapacity() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setLastTick(System.currentTimeMillis() - 3600_000L);
        player.setTax(30);
        player.setCivilianPopulation(1200);
        playerRepository.save(player);
        createBuilding(playerId, "house", 1);
        giveResources(playerId, 0, 0, 0, 0, 0);
        tickService.tick(playerId);
        assertEquals(1200, playerRepository.findById(playerId).orElseThrow().getCivilianPopulation());
        assertEquals(720, getResources(playerId).getGold());
    }

    @ParameterizedTest
    @CsvSource({"999999,1000719", "1000000,1000720", "19999900,20000000",
            "20000000,20000000", "20000001,20000001", "1000000010,1000000010"})
    @DisplayName("黄金生产容量为2000万：未满继续征税，满额停税且不截断超额库存")
    void testGoldProductionCapacity(long initialGold, long expectedGold) throws Exception {
        long now = System.currentTimeMillis();
        try (var ignored = GameClock.use(() -> now)) {
            Player player = playerRepository.findById(playerId).orElseThrow();
            player.setLastTick(now - 3600_000L);
            player.setTax(30);
            player.setCivilianPopulation(1200);
            playerRepository.save(player);
            createBuilding(playerId, "house", 1);
            Resources balance = getResources(playerId);
            balance.setGold(initialGold);
            resourcesRepository.save(balance);

            assertEquals(20_000_000L, tickService.capacity(playerId).get("gold"));
            tickService.tick(playerId);
            assertEquals(expectedGold, getResources(playerId).getGold());
        }
    }

    @Test
    @DisplayName("黄金满额时按持续净收支扣薪与补税，低于2000万后继续正常税收")
    void testGoldTaxResumesBelowProductionCapacity() throws Exception {
        long now = System.currentTimeMillis();
        try (var ignored = GameClock.use(() -> now)) {
            Player player = playerRepository.findById(playerId).orElseThrow();
            player.setLastTick(now - 3600_000L);
            player.setTax(30);
            player.setCivilianPopulation(1200);
            playerRepository.save(player);
            createBuilding(playerId, "house", 1);
            Officer officer = createOfficer(playerId, "idle", 0, 0, 0);
            officer.setSalary(50);
            officerRepository.save(officer);
            giveResources(playerId, 0, 0, 0, 0, 20_000_000);

            tickService.tick(playerId);
            assertEquals(20_000_000L, getResources(playerId).getGold());
            player.setLastTick(now - 3600_000L);
            getResources(playerId).setGold(19_990_000L);
            tickService.tick(playerId);
            assertEquals(19_990_670L, getResources(playerId).getGold());
        }
    }

    @Test
    @DisplayName("士气计算: morale = 100 - tax")
    void testMoraleCalculation() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        long now = System.currentTimeMillis();
        player.setLastTick(now - 3600_000L);
        player.setTax(40);
        playerRepository.save(player);

        tickService.tick(playerId);

        Player updated = playerRepository.findById(playerId).orElseThrow();
        // morale = clamp(100 - 40, 0, 100) = 60
        assertEquals(60, updated.getMorale(), "士气应等于 100 - 税率 = 60");
    }

    @Test
    @DisplayName("离线tick上限: 离线时间超过8小时按8小时计算")
    void testOfflineTickCapped() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        long now = System.currentTimeMillis();
        // Set lastTick to 10 hours ago
        player.setLastTick(now - 10 * 3600_000L);
        player.setTax(0);
        playerRepository.save(player);

        // Create farm level 1 (produces 600/hour)
        createBuilding(playerId, "farm", 1);

        // Start with 0 food, no army
        giveResources(playerId, 0, 0, 0, 0, 0);

        tickService.tick(playerId);

        Resources res = getResources(playerId);
        assertNotNull(res);

        // dt = 10*3600 = 36000 seconds, capped to 8*3600 = 28800
        // hours = 28800 / 3600 = 8
        // food = 0 + 600 * 8 * 1.0 = 4800
        // If not capped, food would be 600 * 10 = 6000
        assertEquals(4800, res.getFood(), "离线10小时应被截断为8小时，产出4800粮食而非6000");
        assertTrue(res.getFood() < 6000, "截断后产出应小于未截断的6000");
    }

    @Test
    @DisplayName("超过生产容量后不再获得自然产量，库存保持超容量数值")
    void testResourcesAboveCapacityStopProduction() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setLastTick(System.currentTimeMillis() - 3600_000L);
        player.setTax(0);
        playerRepository.save(player);

        createBuilding(playerId, "farm", 1);
        createBuilding(playerId, "refinery", 1);
        createBuilding(playerId, "oilfield", 1);
        createBuilding(playerId, "raremine", 1);
        giveResources(playerId, 500_000, 500_000, 500_000, 500_000, 20_000_001);

        tickService.tick(playerId);

        Resources res = getResources(playerId);
        assertEquals(500_000, res.getFood(), "超过粮食上限后不应继续增加");
        assertEquals(500_000, res.getSteel(), "超过钢铁上限后不应继续增加");
        assertEquals(500_000, res.getOil(), "超过石油上限后不应继续增加");
        assertEquals(500_000, res.getRare(), "超过稀矿上限后不应继续增加");
        assertEquals(20_000_001, res.getGold(), "超过黄金生产容量后不应继续增加或截断");
    }

    @Test
    @DisplayName("城市状态倍率: 战争0.7x, 护盾1.1x, 和平1.0x")
    void testCityStatusMultiplier() {
        long now = System.currentTimeMillis();
        int foodBase = 600; // farm level 1 produces 600/hour

        // === Peace (1.0x) ===
        Long peacePlayerId = createTestPlayer("peace_player", 0).getId();
        Player peacePlayer = playerRepository.findById(peacePlayerId).orElseThrow();
        peacePlayer.setLastTick(now - 3600_000L);
        playerRepository.save(peacePlayer);
        createBuilding(peacePlayerId, "farm", 1);
        giveResources(peacePlayerId, 0, 0, 0, 0, 0);
        // No city state => peace
        tickService.tick(peacePlayerId);
        long peaceFood = getResources(peacePlayerId).getFood();
        assertEquals(foodBase, peaceFood, "和平状态产出应为1.0x = 600");

        // === War (0.7x) ===
        Long warPlayerId = createTestPlayer("war_player", 0).getId();
        Player warPlayer = playerRepository.findById(warPlayerId).orElseThrow();
        warPlayer.setLastTick(now - 3600_000L);
        playerRepository.save(warPlayer);
        createBuilding(warPlayerId, "farm", 1);
        giveResources(warPlayerId, 0, 0, 0, 0, 0);
        createCityState(warPlayerId, "war", 0L, now + 3600_000L);
        tickService.tick(warPlayerId);
        long warFood = getResources(warPlayerId).getFood();
        // 600 * 0.7 = 420
        assertEquals(420, warFood, "战争状态产出应为0.7x = 420");

        // === Shield (1.1x) ===
        Long shieldPlayerId = createTestPlayer("shield_player", 0).getId();
        Player shieldPlayer = playerRepository.findById(shieldPlayerId).orElseThrow();
        shieldPlayer.setLastTick(now - 3600_000L);
        playerRepository.save(shieldPlayer);
        createBuilding(shieldPlayerId, "farm", 1);
        giveResources(shieldPlayerId, 0, 0, 0, 0, 0);
        createCityState(shieldPlayerId, "peace", now + 3600_000L, 0L);
        tickService.tick(shieldPlayerId);
        long shieldFood = getResources(shieldPlayerId).getFood();
        // 600 * 1.1 = 660
        assertEquals(660, shieldFood, "护盾状态产出应为1.1x = 660");
    }

    @Test
    @DisplayName("安抚功能: 黄金安抚增加民心与降低民怨")
    void testAppeaseGold() throws Exception {
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setCivilianPopulation(500);
        player.setMorale(50);
        player.setResentment(20);
        playerRepository.save(player);

        Resources res = getResources(playerId);
        res.setGold(5000);
        resourcesRepository.save(res);

        // 500 civilians * 2 = 1000 gold
        Map<String, Object> result;
        long at = player.getLastTick();
        try (var clock = GameClock.use(() -> at)) { result = gameStateService.appease(playerId, "gold"); }
        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals(60, result.get("morale"), "民心应由50上升10点至60");
        assertEquals(15, result.get("resentment"), "民怨应由20下降5点至15");

        Resources updatedRes = getResources(playerId);
        assertEquals(4000, updatedRes.getGold(), "黄金应扣除 1000");
    }

    @Test
    @DisplayName("安抚功能: 钻石特赦大额提升民心并大幅消除民怨")
    void testAppeaseDiamond() throws Exception {
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setMorale(60);
        player.setResentment(35);
        playerRepository.save(player);

        Resources res = getResources(playerId);
        res.setDiamond(50);
        resourcesRepository.save(res);

        Map<String, Object> result;
        long at = player.getLastTick();
        try (var clock = GameClock.use(() -> at)) { result = gameStateService.appease(playerId, "diamond"); }
        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals(85, result.get("morale"), "民心应由60上升25点至85");
        assertEquals(15, result.get("resentment"), "民怨应由35下降20点至15");

        Resources updatedRes = getResources(playerId);
        assertEquals(30, updatedRes.getDiamond(), "钻石应扣除 20");
    }

    @Test
    @DisplayName("税率调整与有效人口容量联动")
    void testSetTaxAndEffectiveCapacity() {
        createBuilding(playerId, "house", 2); // capacity = 200

        Map<String, Object> result = gameStateService.setTax(playerId, 80);
        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals(80, result.get("tax"));

        Map<String, Object> state = gameStateService.getGameState(playerId);
        @SuppressWarnings("unchecked")
        Map<String, Object> pop = (Map<String, Object>) state.get("population");
        assertNotNull(pop);
        assertEquals(2400, pop.get("capacity"));
        int effCap = (Integer) pop.get("effectiveCapacity");
        assertTrue(effCap < 2400, "80%重税下，有效容纳上限应低于标称容量2400");
    }

    @Test
    @DisplayName("行军Tick推送包含完整坐标与兵力")
    void testTickMarchesIncludeFullCoordinatesAndArmy() {
        long now = System.currentTimeMillis();
        createMarch(playerId, "wild", "10", "油田 Lv.4", 50, 60, 55, 65,
                Map.of("fighter", 100), "conquer", now, now + 60000, false, false);

        Map<String, Object> changes = tickService.pushStateChanges(playerId);
        assertNotNull(changes);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> marches = (java.util.List<Map<String, Object>>) changes.get("marches");
        assertNotNull(marches);
        assertEquals(1, marches.size());
        Map<String, Object> m = marches.get(0);
        assertEquals(50, m.get("fromX"), "Tick 推送必须包含 fromX");
        assertEquals(60, m.get("fromY"), "Tick 推送必须包含 fromY");
        assertEquals(55, m.get("targetX"), "Tick 推送必须包含 targetX");
        assertEquals(65, m.get("targetY"), "Tick 推送必须包含 targetY");
        assertNotNull(m.get("distance"), "Tick 推送必须包含 distance");
        assertNotNull(m.get("army"), "Tick 推送必须包含 army 兵力信息");
        @SuppressWarnings("unchecked")
        Map<String, Object> army = (Map<String, Object>) m.get("army");
        assertEquals(100, army.get("fighter"), "兵力应包含 fighter=100");
    }

    @Test
    @DisplayName("施工队列实时推送包含队列ID，保证免费加速能定位工程")
    void testTickConstructionsIncludeQueueId() {
        long now = System.currentTimeMillis();
        Construction construction = createConstruction(playerId, "lab", 1, now - 1000, now + 60000, null);

        Map<String, Object> changes = tickService.pushStateChanges(playerId);

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> constructions =
                (java.util.List<Map<String, Object>>) changes.get("constructions");
        assertNotNull(constructions);
        assertEquals(1, constructions.size());
        assertEquals(construction.getId(), constructions.get(0).get("queueId"));
    }

    @Test
    @DisplayName("平民达到或超过有效容量上限时，Tick推送中的自然增长速度归零")
    void testTickPopulationGrowthZeroAtCapacity() {
        createBuilding(playerId, "house", 2); // capacity = 2400
        Player p = playerRepository.findById(playerId).orElseThrow();
        p.setCivilianPopulation(2400);
        p.setMorale(70);
        playerRepository.save(p);

        Map<String, Object> changes = tickService.pushStateChanges(playerId);
        assertNotNull(changes);
        @SuppressWarnings("unchecked")
        Map<String, Object> pop = (Map<String, Object>) changes.get("population");
        assertNotNull(pop);
        assertEquals(2400, pop.get("civilian"));
        assertEquals(2400, pop.get("effectiveCapacity"));
        assertEquals(0.0, (Double) pop.get("growthPerHour"), 0.001, "平民满额时增长速度必须为0");
    }
}

