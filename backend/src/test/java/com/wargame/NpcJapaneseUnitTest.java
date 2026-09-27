package com.wargame;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.JapaneseUnitDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.BattleResult;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class NpcJapaneseUnitTest {

    @Test
    public void testAllSeventeenUnitsHaveJapaneseNames() {
        assertEquals(17, GameData.UNITS.size(), "游戏应有 17 种标准兵种");
        assertEquals(17, JapaneseUnitDef.NAMES.size(), "日军兵种定义应覆盖全部 17 种兵种");

        for (String unitId : GameData.UNITS.keySet()) {
            UnitDef allied = GameData.UNITS.get(unitId);
            String jName = JapaneseUnitDef.getName(unitId);

            assertNotNull(jName, "兵种 " + unitId + " 必须有日军对应名称");
            assertTrue(jName.contains("-"), "日军兵种名称格式应包含类别连字符: " + jName);

            String alliedCategory = allied.name().split("-")[0];
            String japaneseCategory = jName.split("-")[0];
            assertEquals(alliedCategory, japaneseCategory,
                    "兵种 " + unitId + " 的日军名称类别前缀应与盟军名称一致 (" + alliedCategory + " vs " + japaneseCategory + ")");
        }
    }

    @Test
    public void testNpcBattleOutputsJapaneseUnitNamesInLog() {
        BattleService battleService = new BattleService(42);

        Map<String, Integer> attackerArmy = Map.of("infantry", 100);
        Map<String, Integer> defenderArmy = Map.of("infantry", 50);

        // 模拟 NPC 战斗 (isPlayerBattle = false)
        BattleResult npcBattle = battleService.startWorldDispatch(
                attackerArmy, defenderArmy, Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(),
                10, 0, 10, 0,
                0, 0, "conquer", Collections.emptyMap(), 0,
                false
        );

        String report = npcBattle.getReport();
        assertNotNull(report);
        // 攻方应为盟军/中国远征军步兵
        assertTrue(report.contains("步兵-加兰德步枪兵（M1）"), "我方单位在战报中应使用盟军/远征军名称: \n" + report);
        // 守方 NPC 应为日军步兵
        assertTrue(report.contains("步兵-三八式步枪兵（Type 38）"), "敌方 NPC 在战报中应显示日军专属兵种名称: \n" + report);
    }

    @Test
    public void testPlayerVsPlayerBattleRetainsAlliedUnitNames() {
        BattleService battleService = new BattleService(42);

        Map<String, Integer> attackerArmy = Map.of("infantry", 100);
        Map<String, Integer> defenderArmy = Map.of("infantry", 50);

        // 模拟玩家对战 (isPlayerBattle = true)
        BattleResult pvpBattle = battleService.startWorldDispatch(
                attackerArmy, defenderArmy, Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(),
                10, 0, 10, 0,
                0, 0, "conquer", Collections.emptyMap(), 0,
                true
        );

        String report = pvpBattle.getReport();
        assertNotNull(report);
        assertTrue(report.contains("我方步兵-加兰德步枪兵（M1）"), "PVP 我方应显示盟军名称: \n" + report);
        assertTrue(report.contains("敌方步兵-加兰德步枪兵（M1）"), "PVP 敌方玩家应显示盟军名称而非日军名称: \n" + report);
        assertFalse(report.contains("三八式步枪兵"), "PVP 中不应出现日军专属名称: \n" + report);
    }
}
