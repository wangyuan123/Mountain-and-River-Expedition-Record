package com.wargame;

import com.wargame.model.entity.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TickService 单元测试")
class TickServiceTest extends BaseServiceTest {

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

        // farm: baseProduce=40, curve=1*(1+0.12*0)=1, produce=floor(40*1)=40
        // food = 0 + 40 * 1 hour * 1.0 (peace) = 40
        // No army, so no food consumption
        assertEquals(40, res.getFood(), "农田1级1小时应产出40粮食");

        // refinery: baseProduce=40, same logic => 40
        assertEquals(40, res.getSteel(), "炼钢厂1级1小时应产出40钢铁");

        // oilfield: baseProduce=25 => 25
        assertEquals(25, res.getOil(), "油田1级1小时应产出25石油");

        // raremine: baseProduce=12 => 12
        assertEquals(12, res.getRare(), "稀矿厂1级1小时应产出12稀矿");
    }

    @Test
    @DisplayName("粮食消耗: 部队按规模消耗粮食")
    void testFoodConsumption() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        long now = System.currentTimeMillis();
        player.setLastTick(now - 3600_000L); // 1 hour ago
        player.setTax(0);
        playerRepository.save(player);

        // Create a farm (produces 40 food/hour)
        createBuilding(playerId, "farm", 1);

        // Create 100 infantry (each consumes 1 food/hour => 100 food/hour)
        createArmyUnit(playerId, "infantry", 100);

        // Set initial food to 200
        giveResources(playerId, 200, 0, 0, 0, 0);

        tickService.tick(playerId);

        Resources res = getResources(playerId);
        assertNotNull(res);

        // food production: 0 + 40 = 240 (after production)
        // food consumption: 100 infantry * 1 food * 1.0 (no log_food tech) * 1 hour = 100
        // food final: 240 - 100 = 140
        assertEquals(140, res.getFood(), "200初始粮+40产出-100消耗应等于140");
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

        // Create house level 1 (popPer=100, so pop=100)
        createBuilding(playerId, "house", 1);

        // No mayor, so mayorKnow = 0
        // No officers, so no salary
        giveResources(playerId, 0, 0, 0, 0, 0);

        tickService.tick(playerId);

        Resources res = getResources(playerId);
        assertNotNull(res);

        // goldRate = pop * (tax/100) * (1 + mayorKnow/100) * 2
        // = 100 * (30/100) * (1 + 0) * 2 = 60
        // gold = 0 + 60 * 1 hour = 60
        assertEquals(60, res.getGold(), "100人口*30%税率*2倍率*1小时应产出60黄金");
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

        // Create farm level 1 (produces 40/hour)
        createBuilding(playerId, "farm", 1);

        // Start with 0 food, no army
        giveResources(playerId, 0, 0, 0, 0, 0);

        tickService.tick(playerId);

        Resources res = getResources(playerId);
        assertNotNull(res);

        // dt = 10*3600 = 36000 seconds, capped to 8*3600 = 28800
        // hours = 28800 / 3600 = 8
        // food = 0 + 40 * 8 * 1.0 = 320
        // If not capped, food would be 40 * 10 = 400
        assertEquals(320, res.getFood(), "离线10小时应被截断为8小时，产出320粮食而非400");
        assertTrue(res.getFood() < 400, "截断后产出应小于未截断的400");
    }

    @Test
    @DisplayName("超过资源上限后不再获得产量，库存保持超上限数值")
    void testResourcesAboveCapacityStopProduction() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setLastTick(System.currentTimeMillis() - 3600_000L);
        player.setTax(0);
        playerRepository.save(player);

        createBuilding(playerId, "farm", 1);
        createBuilding(playerId, "refinery", 1);
        createBuilding(playerId, "oilfield", 1);
        createBuilding(playerId, "raremine", 1);
        giveResources(playerId, 500_000, 500_000, 500_000, 500_000, 1_000_000);

        tickService.tick(playerId);

        Resources res = getResources(playerId);
        assertEquals(500_000, res.getFood(), "超过粮食上限后不应继续增加");
        assertEquals(500_000, res.getSteel(), "超过钢铁上限后不应继续增加");
        assertEquals(500_000, res.getOil(), "超过石油上限后不应继续增加");
        assertEquals(500_000, res.getRare(), "超过稀矿上限后不应继续增加");
        assertEquals(1_000_000, res.getGold(), "超过黄金上限后不应继续增加");
    }

    @Test
    @DisplayName("城市状态倍率: 战争0.7x, 护盾1.1x, 和平1.0x")
    void testCityStatusMultiplier() {
        long now = System.currentTimeMillis();
        int foodBase = 40; // farm level 1 produces 40/hour

        // === Peace (1.0x) ===
        Long peacePlayerId = createTestPlayer("peace_player", 0).getId();
        Player peacePlayer = playerRepository.findById(peacePlayerId).orElseThrow();
        peacePlayer.setLastTick(now - 3600_000L);
        playerRepository.save(peacePlayer);
        createBuilding(peacePlayerId, "farm", 1);
        giveResources(peacePlayerId, 0, 0, 0, 0, 0);
        // No city state => peace
        tickService.tick(peacePlayerId);
        int peaceFood = getResources(peacePlayerId).getFood();
        assertEquals(foodBase, peaceFood, "和平状态产出应为1.0x = 40");

        // === War (0.7x) ===
        Long warPlayerId = createTestPlayer("war_player", 0).getId();
        Player warPlayer = playerRepository.findById(warPlayerId).orElseThrow();
        warPlayer.setLastTick(now - 3600_000L);
        playerRepository.save(warPlayer);
        createBuilding(warPlayerId, "farm", 1);
        giveResources(warPlayerId, 0, 0, 0, 0, 0);
        createCityState(warPlayerId, "war", 0L, now + 3600_000L);
        tickService.tick(warPlayerId);
        int warFood = getResources(warPlayerId).getFood();
        // 40 * 0.7 = 28
        assertEquals(28, warFood, "战争状态产出应为0.7x = 28");

        // === Shield (1.1x) ===
        Long shieldPlayerId = createTestPlayer("shield_player", 0).getId();
        Player shieldPlayer = playerRepository.findById(shieldPlayerId).orElseThrow();
        shieldPlayer.setLastTick(now - 3600_000L);
        playerRepository.save(shieldPlayer);
        createBuilding(shieldPlayerId, "farm", 1);
        giveResources(shieldPlayerId, 0, 0, 0, 0, 0);
        createCityState(shieldPlayerId, "peace", now + 3600_000L, 0L);
        tickService.tick(shieldPlayerId);
        int shieldFood = getResources(shieldPlayerId).getFood();
        // 40 * 1.1 = 44
        assertEquals(44, shieldFood, "护盾状态产出应为1.1x = 44");
    }

    @Test
    @DisplayName("安抚功能: 黄金安抚增加民心与降低民怨")
    void testAppeaseGold() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setCivilianPopulation(500);
        player.setMorale(50);
        player.setResentment(20);
        playerRepository.save(player);

        Resources res = getResources(playerId);
        res.setGold(5000);
        resourcesRepository.save(res);

        // 500 civilians * 2 = 1000 gold
        Map<String, Object> result = gameStateService.appease(playerId, "gold");
        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals(60, result.get("morale"), "民心应由50上升10点至60");
        assertEquals(15, result.get("resentment"), "民怨应由20下降5点至15");

        Resources updatedRes = getResources(playerId);
        assertEquals(4000, updatedRes.getGold(), "黄金应扣除 1000");
    }

    @Test
    @DisplayName("安抚功能: 钻石特赦大额提升民心并大幅消除民怨")
    void testAppeaseDiamond() {
        Player player = playerRepository.findById(playerId).orElseThrow();
        player.setMorale(60);
        player.setResentment(35);
        playerRepository.save(player);

        Resources res = getResources(playerId);
        res.setDiamond(50);
        resourcesRepository.save(res);

        Map<String, Object> result = gameStateService.appease(playerId, "diamond");
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
        assertEquals(200, pop.get("capacity"));
        int effCap = (Integer) pop.get("effectiveCapacity");
        assertTrue(effCap < 200, "80%重税下，有效容纳上限应低于标称容量200");
    }
}
