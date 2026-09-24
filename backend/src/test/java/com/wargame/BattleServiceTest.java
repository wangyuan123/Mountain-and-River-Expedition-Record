package com.wargame;

import com.wargame.model.dto.BattleResult;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.constants.UnitDef;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BattleService 单元测试")
class BattleServiceTest {

    private BattleService battleService;

    @BeforeEach
    void setUp() {
        // BattleService has no repository dependencies — only uses static GameData
        battleService = new BattleService(0);
    }

    /**
     * 以无科技、无军官与无城防的固定环境结算单个战术回合。
     *
     * @param attackerArmy 攻方兵力。
     * @param defenderArmy 守方兵力。
     * @param attackerPositions 攻方坐标。
     * @param defenderPositions 守方坐标。
     * @param initialDistance 战场初始宽度。
     * @param attackerOrders 攻方指令。
     * @return 结算后的回合快照。
     */
    private BattleRoundState resolveTacticalRound(Map<String, Integer> attackerArmy,
                                                   Map<String, Integer> defenderArmy,
                                                   Map<String, Integer> attackerPositions,
                                                   Map<String, Integer> defenderPositions,
                                                   int initialDistance,
                                                   Map<String, BattleService.UnitOrder> attackerOrders) {
        return battleService.resolveWorldRound(
                attackerArmy, defenderArmy, attackerPositions, defenderPositions, initialDistance,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                0, 0, 0, 0, 0, 0, 1, attackerOrders, Collections.emptyMap()
        );
    }

    @Test
    @DisplayName("战术空域: 敌方空军或防空装甲车存活时，空军不得越过封锁线")
    void tacticalAirCannotAdvancePastEnemyAirOrAntiAirLine() {
        BattleRoundState state = resolveTacticalRound(
                Map.of("fighter", 1), Map.of("armored", 1),
                Map.of("fighter", 2700), Map.of("armored", 3000), 5000,
                Map.of("fighter", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE))
        );

        assertTrue(state.attackerPositions().get("fighter") > 2700,
                "进入射程后仍应向封锁线前进");
        assertTrue(state.attackerPositions().get("fighter") <= state.defenderPositions().get("armored"),
                "战斗机不能越过防空封锁线");
    }

    @Test
    @DisplayName("战术空域: 敌方无空军和防空装甲车时，空军可越过地面前排进入纵深")
    void tacticalAirCanBypassGroundLineWhenAirspaceIsOpen() {
        BattleRoundState state = resolveTacticalRound(
                Map.of("fighter", 1), Map.of("infantry", 100, "rocket", 1),
                Map.of("fighter", 1400), Map.of("infantry", 1500, "rocket", 4000), 5000,
                Map.of("fighter", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE))
        );

        assertEquals(1900, state.attackerPositions().get("fighter"),
                "空域开放后，战斗机应越过普通地面前排并继续向敌方纵深推进");
    }

    @Test
    @DisplayName("战术空域: 空中封锁下未指定目标攻击最近单位，玩家可指定其他射程内目标")
    void tacticalAirDefaultsToNearestTargetAtBlockadeLine() {
        Map<String, Integer> defenderArmy = Map.of("armored", 10, "fighter", 1);
        Map<String, Integer> defenderPositions = Map.of("armored", 3000, "fighter", 3030);
        BattleRoundState automatic = resolveTacticalRound(
                Map.of("fighter", 1), defenderArmy,
                Map.of("fighter", 2700), defenderPositions, 5000, Collections.emptyMap()
        );
        BattleRoundState focused = resolveTacticalRound(
                Map.of("fighter", 1), defenderArmy,
                Map.of("fighter", 2700), defenderPositions, 5000,
                Map.of("fighter", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE, "fighter"))
        );

        assertTrue(automatic.log().contains("空战敌战斗机"),
                "未指定目标时，空军应攻击推进后最近的合法目标");
        assertTrue(focused.log().contains("空战敌战斗机"),
                "玩家指定后，空军应可攻击射程内任意合法单位");
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
        // Attacker: 100 heavy tanks (atk=24, def=60, hp=420 each) — overwhelmingly stronger
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

        // 使用能覆盖步兵射程的榴弹炮验证城防火力，避免依赖败战时额外扣除幸存者。
        Map<String, Integer> withForts = Map.of("howitzer", 20);

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
        // howitzer def=6, count=20 => 6*20 = 120
        assertEquals(120, fortBonus, "20座榴弹炮(def=6)的防御加成应为120");
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
        // 守方: 10辆重型坦克 (def=60, hp=420)
        Map<String, Integer> garrison = Map.of("htank", 10);
        // 攻方: 1个步兵 (atk=6)
        Map<String, Integer> attacker = Map.of("infantry", 1);

        BattleResult result = battleService.resolveWild(garrison, attacker);

        // 线性微量火力只产生极小的概率击杀，不得保底击毁一辆重坦。
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
        assertFalse(report.contains("我方" + UnitDef.UNITS.get("truck").name() + "(5) 前进"), "卡车不应主动冲锋前进");
    }

    @Test
    @DisplayName("野地战斗动态距离与两阶段机动推进验证")
    void testWildBattleDistance() {
        BattleResult result = battleService.resolveWild(Map.of("infantry", 10), Map.of("infantry", 10));
        assertTrue(result.getReport().contains("战场初始距离: 1000"), "双方步兵(spd=3, range=100)保底初始距离应为 1000");
        assertTrue(result.getReport().contains("[前进] 推进150 -> 坐标150"), "第一回合攻方步兵推进150");
    }

    @Test
    @DisplayName("战术指令: 验证后退与阵地边界底线规则")
    void testCommandActionRetreatAndHold() {
        // 攻方下达后退指令，初始在坐标 0 (已在阵地底线)
        Map<String, BattleService.UnitOrder> attackerOrders = Map.of(
                "infantry", new BattleService.UnitOrder(BattleService.CommandAction.RETREAT)
        );
        BattleResult result = battleService.resolveWild(Map.of("infantry", 10), Map.of("infantry", 10),
                attackerOrders, null);

        String report = result.getReport();
        assertTrue(report.contains("[后退] 已达阵地底线(坐标0) 退无可退"), "阵地底线0不可穿透");
    }

    @Test
    @DisplayName("战术指令: 指定集火目标与重坦掩护机制")
    void testCommandFocusTargetWithTankCover() {
        // 守方有重坦与火炮
        Map<String, Integer> garrison = Map.of("htank", 5, "howitzer", 5);
        // 攻方步兵指定集火后排榴弹炮，但受重坦前排掩护阻挡
        Map<String, BattleService.UnitOrder> attackerOrders = Map.of(
                "infantry", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE, "howitzer")
        );

        BattleResult result = battleService.resolveWild(garrison, Map.of("infantry", 100), attackerOrders, null);
        String report = result.getReport();
        // 步兵无法穿透重坦掩护直接打榴弹炮，应优先攻击前排重坦
        assertTrue(report.contains("敌重型坦克"), "步兵面对重坦掩护应优先承伤攻击重坦");
    }

    @Test
    @DisplayName("指挥官属性: 验证守方防御属性对冲与战报日志显示")
    void testCommanderDefenseAttributeMitigation() {
        // 双方 100 步兵，第 3 回合军官加成生效：攻方军事 100，守方防御 100
        BattleResult result = battleService.startWorldDispatch(
                Map.of("infantry", 100),
                Map.of("infantry", 100),
                Map.of(),
                Map.of(), Map.of(),
                Map.of(), Map.of(),
                100, 0, // 攻方 mil=100, def=0
                0, 100, // 守方 mil=0, def=100
                0, 0,
                "conquer",
                Map.of(), 0,
                true);

        String report = result.getReport();
        assertTrue(report.contains("我方将领加成：军事属性 +100%攻击"), "战报应体现攻方军事加成");
        assertTrue(report.contains("敌方将领加成：防御属性 +100%防御"), "战报应体现守方防御加成");
    }

    @Test
    @DisplayName("军官技能: 绝境反击受击存活后以100%火力反击")
    void testCounterSkillTriggersCounterattack() {
        // 守方拥有 绝境反击 Lv.5 (触发概率 52%，100% 火力反击)
        BattleResult result = battleService.startWorldDispatch(
                Map.of("infantry", 100),
                Map.of("infantry", 100),
                Map.of(),
                Map.of(), Map.of(),
                Map.of(), Map.of("counter", 5),
                0, 0,
                0, 0,
                0, 0,
                "conquer",
                Map.of(), 0,
                true);

        String report = result.getReport();
        assertTrue(report.contains("[绝境反击]"), "战报应包含绝境反击日志");
    }
}
