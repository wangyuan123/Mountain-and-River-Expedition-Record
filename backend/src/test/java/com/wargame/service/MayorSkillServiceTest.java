package com.wargame.service;

import com.wargame.BaseServiceTest;
import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Resources;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MayorSkillServiceTest extends BaseServiceTest {

    @Autowired private TechService techService;
    @Autowired private OfficerService officerService;
    @Autowired private CityScope cityScope;
    @Autowired private ArmyService armyService;

    @Test
    void skillPoolContainsAllFifteenSkills() {
        assertEquals(15, OfficerSkillDef.OFFICER_SKILLS.size(), "技能池应包含15个技能");
        assertTrue(OfficerSkillDef.OFFICER_SKILLS.containsKey("harvest"), "应包含屯田技能");
        assertTrue(OfficerSkillDef.OFFICER_SKILLS.containsKey("construct"), "应包含营造技能");
        assertTrue(OfficerSkillDef.OFFICER_SKILLS.containsKey("finance"), "应包含理财技能");
        assertTrue(OfficerSkillDef.OFFICER_SKILLS.containsKey("research"), "应包含格物技能");
        assertTrue(OfficerSkillDef.OFFICER_SKILLS.containsKey("leadership"), "应包含统帅技能");
        assertTrue(OfficerSkillDef.OFFICER_SKILLS.containsKey("ration"), "应包含军屯技能");
        assertTrue(OfficerSkillDef.OFFICER_SKILLS.containsKey("counter"), "应包含绝境反击技能");
        assertTrue(OfficerSkillDef.OFFICER_SKILLS.containsKey("learn"), "应包含师夷长技技能");
        assertTrue(OfficerSkillDef.OFFICER_SKILLS.containsKey("borrow_armor"), "应包含借甲御敌技能");
        assertTrue(OfficerSkillDef.conflictsWith("bulwark", "borrow_armor"), "坚守阵地与借甲御敌应互斥");
        assertFalse(OfficerSkillDef.OFFICER_SKILLS.containsKey("supply"), "技能池不应直接包含旧补给技能");
        assertEquals("leadership", OfficerSkillDef.getSkill("supply").key(), "旧补给技能应平滑映射至统帅");
        for (var def : OfficerSkillDef.OFFICER_SKILLS.values()) {
            assertEquals(4, def.name().length(), "技能「" + def.name() + "」名称应为4个字符");
        }
    }

    @Test
    void mayorHarvestSkillIncreasesResourceProduction() {
        Long playerId = createTestPlayer("harvest-player", 30).getId();
        createBuilding(playerId, "farm", 5);

        // 无市长产出
        double baseFood = tickService.produceOf(playerId, "farm");
        assertTrue(baseFood > 0);

        // 任命带 屯田 Lv.5 的市长（+25%）
        Officer mayor = new Officer();
        mayor.setPlayerId(playerId);
        mayor.setCitySlot(0);
        mayor.setName("内政官");
        mayor.setRole("mayor");
        mayor.setLevel(1);
        mayor.setLogistics(0);
        mayor.setKnowledge(0);
        mayor.setMilitary(0);
        mayor.setDefense(0);
        mayor.setSkills(JsonUtil.toJson(List.of(Map.of("id", "harvest", "lv", 5))));
        officerRepository.save(mayor);

        double foodWithMayor = tickService.produceOf(playerId, "farm");
        // 产出应乘以 1.50
        assertEquals(Math.floor(baseFood * 1.50), foodWithMayor, 1.0);
    }

    @Test
    void mayorFinanceSkillIncreasesGoldTaxRate() {
        Long playerId = createTestPlayer("finance-player", 20).getId();

        // 创建市长带 理财 Lv.5（+40%）
        Officer mayor = new Officer();
        mayor.setPlayerId(playerId);
        mayor.setCitySlot(0);
        mayor.setName("财政官");
        mayor.setRole("mayor");
        mayor.setLevel(1);
        mayor.setLogistics(0);
        mayor.setKnowledge(50); // 学识 50
        mayor.setMilitary(0);
        mayor.setDefense(0);
        mayor.setSkills(JsonUtil.toJson(List.of(Map.of("id", "finance", "lv", 5))));
        officerRepository.save(mayor);

        cityScope.economy(playerId).setCivilianPopulation(100);
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
        res.setGold(0);
        resourcesRepository.save(res);

        // 设置 1 小时前的心跳时间，模拟离线/挂机产出结算
        cityScope.economy(playerId).setLastTick(System.currentTimeMillis() - 3600_000L);
        tickService.tick(playerId);
        Resources updated = resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
        assertTrue(updated.getGold() > 0, "应获得税收黄金");
    }

    @Test
    void mayorConstructSkillReducesBuildDuration() {
        Long playerId = createTestPlayer("construct-player", 30).getId();

        // 任命带 营造 Lv.5 的市长（工期-30%）
        Officer mayor = new Officer();
        mayor.setPlayerId(playerId);
        mayor.setCitySlot(0);
        mayor.setName("工部尚书");
        mayor.setRole("mayor");
        mayor.setLevel(1);
        mayor.setLogistics(0);
        mayor.setKnowledge(0);
        mayor.setMilitary(0);
        mayor.setDefense(0);
        mayor.setSkills(JsonUtil.toJson(List.of(Map.of("id", "construct", "lv", 5))));
        officerRepository.save(mayor);

        // 升级建筑验证工期
        createBuilding(playerId, "command", 1);
        createBuilding(playerId, "house", 0);
        Map<String, Object> res = buildService.upgrade(playerId, "house", 0);
        assertTrue((Boolean) res.get("success"));
        int duration = ((Number) res.get("buildTime")).intValue();
        // 基础30秒，营造Lv.5缩短20%：ceil(30 * 0.8) = 24秒
        assertEquals(24, duration, "营造Lv.5应缩短20%工期到24秒");
    }

    @Test
    void mayorResearchSkillAcceleratesTechResearch() {
        Long playerId = createTestPlayer("research-player", 30).getId();
        createBuilding(playerId, "lab", 5);

        int durationWithoutMayor = techService.calcTechDuration(playerId, "mil_infantry", 0, 5);

        // 任命带 格物 Lv.5 的市长（研发速度+20%）
        Officer mayor = new Officer();
        mayor.setPlayerId(playerId);
        mayor.setCitySlot(0);
        mayor.setName("大学士");
        mayor.setRole("mayor");
        mayor.setLevel(1);
        mayor.setLogistics(0);
        mayor.setKnowledge(0);
        mayor.setMilitary(0);
        mayor.setDefense(0);
        mayor.setSkills(JsonUtil.toJson(List.of(Map.of("id", "research", "lv", 5))));
        officerRepository.save(mayor);

        int durationWithMayor = techService.calcTechDuration(playerId, "mil_infantry", 0, 5);
        assertTrue(durationWithMayor < durationWithoutMayor, "格物技能应显著缩短研发时间");
        // base = 30, labSpeed = 1 + 0.1 * 4 = 1.4 -> durationWithoutMayor = round(30 / 1.4) = 21
        // mayorSpeed = 1 + 0.04 * 5 = 1.2 -> durationWithMayor = round(30 / (1.4 * 1.2)) = 18
        assertEquals(18, durationWithMayor, "格物Lv.5应加速研发");
    }

    @Test
    void commanderLeadershipSkillIncreasesArmyCap() {
        Long playerId = createTestPlayer("lead-player", 30).getId();
        for (int tier = 1; tier <= MilitaryRankDef.MAX_RANK_TIER; tier++) {
            assertEquals(tier * 50000, MilitaryRankDef.getRankBase(tier));
        }
        int capWithoutOfficer = armyService.armyCap(playerId);
        assertEquals(50000, capWithoutOfficer);

        createBuilding(playerId, "command", 10);
        createBuilding(playerId, "staff", 10);
        assertEquals(50000, armyService.armyCap(playerId));

        var player = playerRepository.findById(playerId).orElseThrow();
        player.setMilitaryRank(17);
        playerRepository.save(player);
        assertEquals(850000, armyService.armyCap(playerId));
        var wall = createBuilding(playerId, "wall", 9);
        assertEquals(850000, armyService.armyCap(playerId));
        wall.setLevel(10);
        buildingRepository.save(wall);
        assertEquals(950000, armyService.armyCap(playerId));

        Officer cmd = new Officer();
        cmd.setPlayerId(playerId);
        cmd.setCitySlot(0);
        cmd.setName("普通指挥官");
        cmd.setRole("commander");
        cmd.setLevel(100);
        cmd.setLogistics(0);
        cmd.setKnowledge(0);
        cmd.setMilitary(50);
        cmd.setDefense(0);
        cmd.setSkills(JsonUtil.toJson(List.of()));
        officerRepository.save(cmd);

        int capWithNormalCmd = armyService.armyCap(playerId);
        assertEquals(950000, capWithNormalCmd);

        // 升级统帅技能 Lv.5 (+20%)
        cmd.setSkills(JsonUtil.toJson(List.of(Map.of("id", "leadership", "lv", 5))));
        officerRepository.save(cmd);

        int capWithLeadCmd = armyService.armyCap(playerId);
        assertEquals(1140000, capWithLeadCmd, "统帅Lv.5应提升基础与围墙奖励之和的20%");

        // 兼容旧 supply 技能
        cmd.setSkills(JsonUtil.toJson(List.of(Map.of("id", "supply", "lv", 5))));
        officerRepository.save(cmd);
        assertEquals(1140000, armyService.armyCap(playerId), "旧supply技能应向后兼容统帅效果");
    }

    @Test
    void mayorRationSkillReducesFoodConsumption() {
        Long playerId = createTestPlayer("ration-player", 20).getId();
        createArmyUnit(playerId, "infantry", 100); // 100 步兵，每小时消耗 100 粮

        // 军屯 Lv.5 市长（-50% 耗粮）
        Officer mayor = new Officer();
        mayor.setPlayerId(playerId);
        mayor.setCitySlot(0);
        mayor.setName("内务官");
        mayor.setRole("mayor");
        mayor.setLevel(1);
        mayor.setLogistics(0);
        mayor.setKnowledge(0);
        mayor.setMilitary(0);
        mayor.setDefense(0);
        mayor.setSkills(JsonUtil.toJson(List.of(Map.of("id", "ration", "lv", 5))));
        officerRepository.save(mayor);

        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
        res.setFood(1000);
        resourcesRepository.save(res);

        cityScope.economy(playerId).setCivilianPopulation(100);
        cityScope.economy(playerId).setLastTick(System.currentTimeMillis() - 3600_000L); // 模拟1小时

        tickService.tick(playerId);

        Resources updated = resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
        // 原耗粮 100/h，军屯Lv.5 降低80% -> 实际耗粮 20/h。初始 1000 - 20 = 980
        assertEquals(980, updated.getFood(), "军屯Lv.5应降低80%养兵耗粮");
    }
}
