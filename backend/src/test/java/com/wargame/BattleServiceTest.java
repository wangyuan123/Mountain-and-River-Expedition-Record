package com.wargame;

import com.wargame.model.dto.BattleResult;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BattleService 单元测试")
class BattleServiceTest {

    private BattleService battleService;

    @BeforeEach
    void setUp() {
        // BattleService has no repository dependencies — only uses static GameData
        battleService = new BattleService();
    }

    @Test
    @DisplayName("野地战斗: 攻方击败守方驻军")
    void testResolveWild() {
        // Garrison: 10 infantry
        Map<String, Integer> garrison = Map.of("infantry", 10);
        // Attacker: 100 infantry (10x stronger)
        Map<String, Integer> attacker = Map.of("infantry", 100);

        BattleResult result = battleService.resolveWild(garrison, attacker);

        assertNotNull(result);
        assertTrue(result.isWin(), "100步兵应击败10步兵驻军");
        assertNotNull(result.getSurvivorAttacker());
        assertTrue(result.getSurvivorAttacker().getOrDefault("infantry", 0) > 0,
                "攻方应有幸存部队");
        // Defender should be wiped out
        assertEquals(0, result.getSurvivorDefender().getOrDefault("infantry", 0),
                "守方应被全歼");
        assertFalse(result.isCityConquered(), "野地战斗不应触发城市征服");
    }

    @Test
    @DisplayName("强弱悬殊: 10倍战力的军队应获胜")
    void testStrongerArmyWins() {
        // Defender: 10 infantry (atk=6, def=4, hp=30 each)
        Map<String, Integer> garrison = Map.of("infantry", 10);
        // Attacker: 100 heavy tanks (atk=50, def=40, hp=220 each) — overwhelmingly stronger
        Map<String, Integer> attacker = Map.of("htank", 100);

        BattleResult result = battleService.resolveWild(garrison, attacker);

        assertTrue(result.isWin(), "100重型坦克应轻松击败10步兵");
        // Attacker should have minimal losses
        int survivors = result.getSurvivorAttacker().getOrDefault("htank", 0);
        assertEquals(100, survivors, "重型坦克面对10步兵应无损失");
    }

    @Test
    @DisplayName("势均力敌: 双方均应有显著损失")
    void testEqualArmies() {
        // Both sides: 100 infantry each
        Map<String, Integer> garrison = Map.of("infantry", 100);
        Map<String, Integer> attacker = Map.of("infantry", 100);

        BattleResult result = battleService.resolveWild(garrison, attacker);

        // Both sides should have taken significant losses
        int attackerSurvivors = result.getSurvivorAttacker().getOrDefault("infantry", 0);
        int defenderSurvivors = result.getSurvivorDefender().getOrDefault("infantry", 0);

        // Attacker started with 100, should have lost some
        assertTrue(attackerSurvivors < 100, "攻方应有损失，幸存者应少于100");
        // Defender should be wiped out or nearly wiped out (attacker acts first with equal stats)
        assertTrue(defenderSurvivors < 100, "守方应有损失");
        // Total casualties should be significant
        int totalLost = (100 - attackerSurvivors) + (100 - defenderSurvivors);
        assertTrue(totalLost > 50, "双方总损失应超过50单位");
    }

    @Test
    @DisplayName("城防加成: 守方城防设施提供防御优势")
    void testFortBonus() {
        // Scenario 1: No forts — attacker vs defender army only
        Map<String, Integer> attackerArmy = Map.of("infantry", 200);
        Map<String, Integer> defenderArmy = Map.of("infantry", 50);
        Map<String, Integer> noForts = Map.of();

        BattleResult resultNoForts = battleService.startWorldDispatch(
                attackerArmy, defenderArmy, noForts,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0,
                "conquer", Map.of(), 0);

        // Scenario 2: With forts — bunkers add defense
        Map<String, Integer> withForts = Map.of("bunker", 20);

        BattleResult resultWithForts = battleService.startWorldDispatch(
                attackerArmy, defenderArmy, withForts,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0,
                "conquer", Map.of(), 0);

        // With forts, attacker should have fewer survivors (forts fight back)
        int survivorsNoForts = resultNoForts.getSurvivorAttacker().getOrDefault("infantry", 0);
        int survivorsWithForts = resultWithForts.getSurvivorAttacker().getOrDefault("infantry", 0);

        assertTrue(survivorsWithForts < survivorsNoForts,
                "有城防时攻方幸存者应少于无城防时");

        // Also verify calcFortBonus returns correct value
        int fortBonus = battleService.calcFortBonus(withForts);
        // bunker def=14, count=20 => 14*20 = 280
        assertEquals(280, fortBonus, "20座碉堡(def=14)的防御加成应为280");
    }

    @Test
    @DisplayName("掠夺计算: 仓库保护资源不被掠夺")
    void testPlunderCalculation() {
        // Defender has resources
        Map<String, Integer> defenderResources = Map.of(
                "food", 5000,
                "steel", 3000,
                "oil", 2000,
                "rare", 1000,
                "gold", 500
        );
        // Warehouse level 2: protection = floor(2 * 1000) = 2000
        long warehouseLevel = 2;

        Map<String, Integer> plunder = battleService.calcPlunder(defenderResources, warehouseLevel);

        assertNotNull(plunder);
        // food: max(0, 5000 - 2000) = 3000
        assertEquals(3000, plunder.get("food"), "粮食掠夺量 = 5000 - 2000保护 = 3000");
        // steel: max(0, 3000 - 2000) = 1000
        assertEquals(1000, plunder.get("steel"), "钢铁掠夺量 = 3000 - 2000保护 = 1000");
        // oil: max(0, 2000 - 2000) = 0
        assertEquals(0, plunder.get("oil"), "石油掠夺量 = max(0, 2000-2000) = 0");
        // rare: max(0, 1000 - 2000) = 0
        assertEquals(0, plunder.get("rare"), "稀矿掠夺量 = max(0, 1000-2000) = 0");
        // gold: not protected by warehouse
        assertEquals(500, plunder.get("gold"), "黄金不受仓库保护，应全额掠夺");

        // Also test with zero warehouse level
        Map<String, Integer> plunderNoWarehouse = battleService.calcPlunder(defenderResources, 0);
        // With no warehouse, protection = 0, all resources plunderable
        assertEquals(5000, plunderNoWarehouse.get("food"), "无仓库时粮食应全额掠夺");
        assertEquals(500, plunderNoWarehouse.get("gold"), "黄金始终全额掠夺");
    }

    @Test
    @DisplayName("概率击杀: 单个步兵对重型坦克的微量伤害不应强制击毁")
    void testMicroDamageDoesNotGuaranteeKill() {
        // 守方: 10辆重型坦克 (def=40, hp=220)
        Map<String, Integer> garrison = Map.of("htank", 10);
        // 攻方: 1个步兵 (atk=6)
        Map<String, Integer> attacker = Map.of("infantry", 1);

        BattleResult result = battleService.resolveWild(garrison, attacker);

        // 步兵对重坦单次伤害 dmg = (6*6*1)/(40*10) = 0.09
        // 0.09 / 220 约 0.0004 概率，绝大多数情况下坦克无损
        assertFalse(result.isWin(), "1个步兵无法击败10辆重型坦克");
        int survivingTanks = result.getSurvivorDefender().getOrDefault("htank", 0);
        assertEquals(10, survivingTanks, "微量伤害不应强制击毁重型坦克");
    }

    @Test
    @DisplayName("非战斗单位不冲锋: 卡车有 autoAdvance=false, 不应在射程外盲目冲锋")
    void testTruckDoesNotAutoAdvanceOutOfRange() {
        // 攻方携带卡车出征: 10辆重型坦克 + 5辆卡车
        Map<String, Integer> attacker = Map.of("htank", 10, "truck", 5);
        // 守方: 20个步兵
        Map<String, Integer> defender = Map.of("infantry", 20);

        BattleResult result = battleService.resolveWild(defender, attacker);

        assertTrue(result.isWin(), "重型坦克掩护下应获胜");
        // 战斗日志中，卡车(range=0)在射程外不应有前进日志，直到距离进入0或战斗结束
        String report = result.getReport();
        assertFalse(report.contains("我方卡车(5) 前进"), "卡车不应主动冲锋前进");
    }

    @Test
    @DisplayName("野地初始交战距离: 修复+2000硬编码后应基于双方航速合理进入射程")
    void testWildBattleDistanceNoLegacy2000() {
        // 双方均为步兵 (range 100, spd 3)
        Map<String, Integer> attacker = Map.of("infantry", 10);
        Map<String, Integer> defender = Map.of("infantry", 10);

        BattleResult result = battleService.resolveWild(defender, attacker);
        String report = result.getReport();

        // 原先加了2000，初始距离2100，步兵每回合走150需要14回合才接敌
        // 修复后初始距离为 100 + 3 * 50 = 250，步兵走150后距离100，第1-2回合即可开火
        assertFalse(report.contains("距离->1950"), "野地战斗不应有遗留的+2000超大距离");
    }
}
