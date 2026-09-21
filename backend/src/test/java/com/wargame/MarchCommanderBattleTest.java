package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("行军出征指定将领参战与战报加成测试")
class MarchCommanderBattleTest extends BaseServiceTest {

    private Long playerId;
    private Long worldId;
    private NpcCity npcCity;

    @BeforeEach
    void setUp() {
        Player player = createTestPlayer("nimitz_player", 30);
        playerId = player.getId();

        WorldMap world = createTestWorld();
        worldId = world.getId();

        // 创建一个测试用的 NPC 城市 (斯德哥尔摩)
        npcCity = new NpcCity();
        npcCity.setWorldId(worldId);
        npcCity.setName("斯德哥尔摩");
        npcCity.setLevel(2);
        npcCity.setX(20);
        npcCity.setY(20);
        npcCity.setArmy(JsonUtil.toJson(Map.of("infantry", 20)));
        npcCity.setForts(JsonUtil.toJson(Map.of()));
        npcCity.setResources(JsonUtil.toJson(Map.of("food", 500, "steel", 500, "oil", 500, "rare", 200, "gold", 100)));
        npcCity.setDefeated(false);
        npcCity = npcCityRepository.save(npcCity);
    }

    @Test
    @DisplayName("指派闲置将领(尼米兹)带兵攻击NPC: 战报应显示尼米兹参战，属性技能生效且获得战斗经验")
    void testDispatchWithIdleOfficerNimitz() {
        // 1. 创建一名闲置将领“尼米兹” (role 为 null, 未任命为常驻司令)
        Officer nimitz = new Officer();
        nimitz.setPlayerId(playerId);
        nimitz.setName("尼米兹");
        nimitz.setStar(5);
        nimitz.setLevel(1);
        nimitz.setExp(0L);
        nimitz.setMilitary(80);
        nimitz.setLogistics(60);
        nimitz.setKnowledge(70);
        nimitz.setLoyalty(100);
        nimitz.setRole(null);
        nimitz.setSkills(JsonUtil.toJson(List.of(Map.of("id", "blitz", "lv", 2))));
        nimitz = officerRepository.save(nimitz);

        // 2. 准备 200 辆轻型坦克
        createArmyUnit(playerId, "ltank", 200);

        // 3. 发起出征，明确指定 commanderId 为尼米兹的 ID
        DispatchRequest req = new DispatchRequest(
                "npc", npcCity.getId(), "conquer",
                Map.of("ltank", 100),
                nimitz.getId(),
                Map.of("food", 0, "steel", 0, "oil", 0, "rare", 0)
        );

        March march = marchService.createDispatch(playerId, req);
        assertNotNull(march);
        assertEquals(nimitz.getId(), march.getCommanderId(), "行军记录应绑定尼米兹作为指挥官");

        // 4. 模拟时间流逝，行军到达目标并触发战斗结算
        long arriveTime = march.getArriveAt() + 1000L;
        marchService.processMarches(playerId, arriveTime);

        // 5. 校验战报
        List<ScoutReport> reports = scoutReportRepository.findByPlayerId(playerId);
        assertFalse(reports.isEmpty(), "应生成至少一条战斗战报");
        ScoutReport latestReport = reports.get(reports.size() - 1);
        assertEquals("battle", latestReport.getType());

        Map<String, Object> data = JsonUtil.parseObjMap(latestReport.getData());
        assertNotNull(data);
        assertTrue(Boolean.TRUE.equals(data.get("win")), "我方优势兵力应取得胜利");

        // 核心断言: 攻方将领必须是尼米兹，绝不能为 null 或显示无将领参战
        Map<String, Object> commanders = (Map<String, Object>) data.get("commanders");
        assertNotNull(commanders, "战报应包含 commanders 信息");
        Map<String, Object> attackerCmd = (Map<String, Object>) commanders.get("attacker");
        assertNotNull(attackerCmd, "攻方将领不能为 null (不能是'无将领参战')");
        assertEquals("尼米兹", attackerCmd.get("name"), "攻方将领名字应为尼米兹");
        assertEquals(80, ((Number) attackerCmd.get("military")).intValue(), "将领军事属性应生效");

        // 技能断言
        List<Map<String, Object>> skills = (List<Map<String, Object>>) attackerCmd.get("skills");
        assertNotNull(skills);
        assertFalse(skills.isEmpty(), "尼米兹的技能列表应存在");
        assertEquals("闪电突击", skills.get(0).get("name"));

        // 核心断言: 尼米兹必须获得战斗结算经验 (原先遗漏)
        Officer refreshedNimitz = officerRepository.findById(nimitz.getId()).orElseThrow();
        assertTrue(refreshedNimitz.getExp() > 0L, "战斗胜利后尼米兹应获得经验值，当前经验: " + refreshedNimitz.getExp());
    }

    @Test
    @DisplayName("主城常驻指挥官A存在时，出征特地指定将领B，应以出征指定的将领B参与战斗与获取经验")
    void testDispatchPrefersDesignatedOfficerOverResidentCommander() {
        // 主城常驻司令: 巴顿 (role = "commander")
        Officer patton = createOfficer(playerId, "commander", 90, 50, 60);
        patton.setName("巴顿");
        patton.setExp(0L);
        patton = officerRepository.save(patton);

        // 出征指定将领: 尼米兹 (role = null)
        Officer nimitz = createOfficer(playerId, null, 75, 80, 85);
        nimitz.setName("尼米兹");
        nimitz.setExp(0L);
        nimitz = officerRepository.save(nimitz);

        createArmyUnit(playerId, "ltank", 100);

        DispatchRequest req = new DispatchRequest(
                "npc", npcCity.getId(), "conquer",
                Map.of("ltank", 50),
                nimitz.getId(),
                Map.of("food", 0, "steel", 0, "oil", 0, "rare", 0)
        );

        March march = marchService.createDispatch(playerId, req);
        long arriveTime = march.getArriveAt() + 1000L;
        marchService.processMarches(playerId, arriveTime);

        // 验证战报攻方将领是尼米兹，而非主城的巴顿
        List<ScoutReport> reports = scoutReportRepository.findByPlayerId(playerId);
        ScoutReport latestReport = reports.get(reports.size() - 1);
        Map<String, Object> data = JsonUtil.parseObjMap(latestReport.getData());
        Map<String, Object> commanders = (Map<String, Object>) data.get("commanders");
        Map<String, Object> attackerCmd = (Map<String, Object>) commanders.get("attacker");

        assertNotNull(attackerCmd);
        assertEquals("尼米兹", attackerCmd.get("name"), "战报应记录实际带兵的尼米兹，而非巴顿");

        // 验证经验加给了尼米兹，而巴顿未参战不加经验
        Officer updatedNimitz = officerRepository.findById(nimitz.getId()).orElseThrow();
        Officer updatedPatton = officerRepository.findById(patton.getId()).orElseThrow();

        assertTrue(updatedNimitz.getExp() > 0L, "参战的尼米兹应获得经验");
        assertEquals(0L, updatedPatton.getExp(), "未带兵出征的主城司令巴顿不应获得该战斗经验");
    }
}
