package com.wargame;

import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.OfficerEquipmentRepository;
import com.wargame.service.CityScope;
import com.wargame.service.MarchTargetService;
import com.wargame.service.OfficerService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证岗位独立性、城市范围、装备套装、野地手动快照和自动战报。 */
class HeadquartersServiceTest extends BaseServiceTest {
    @Autowired OfficerService officers;
    @Autowired CityScope scope;
    @Autowired MarchTargetService targets;
    @Autowired OfficerEquipmentRepository equipment;
    @Autowired BattleSessionRepository sessions;
    private Long playerId;
    private WildTile wild;

    @BeforeEach void setup() {
        playerId = createTestPlayer("chief", 30).getId();
        wild = createWildTile(createTestWorld().getId(), "forest", 15, 15, 1, Map.of("infantry", 100), 1000);
    }

    @Test void appointmentRequiresHeadquartersAndPreservesOtherPositions() {
        Officer mayor = createOfficer(playerId, "mayor", 20, 30, 100, 100);
        Officer commander = createOfficer(playerId, "commander", 100, 100, 20, 20);
        Officer chief = createOfficer(playerId, "idle", 150, 140, 120, 110);
        assertThrows(IllegalArgumentException.class, () -> officers.appoint(playerId, chief.getId(), "commander_in_chief"));
        createBuilding(playerId, "headquarters", 1);
        assertEquals(true, officers.appoint(playerId, chief.getId(), "commander_in_chief").get("success"));
        assertEquals("mayor", officerRepository.findById(mayor.getId()).orElseThrow().getRole());
        assertEquals("commander", officerRepository.findById(commander.getId()).orElseThrow().getRole());
        Officer replacement = createOfficer(playerId, "idle", 160, 140, 100, 100);
        officers.appoint(playerId, replacement.getId(), "commander_in_chief");
        assertEquals("idle", officerRepository.findById(chief.getId()).orElseThrow().getRole());
        assertEquals(1, officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, 0, "commander_in_chief").size());
        officers.appoint(playerId, replacement.getId(), "commander_in_chief");
        assertNull(targets.getCommanderInChief(playerId, 0));
    }

    @Test void invalidForeignMarchingOfficersAndChiefSortiesAreRejected() {
        createBuilding(playerId, "headquarters", 1);
        Officer chief = createOfficer(playerId, "commander_in_chief", 100, 100, 100);
        Officer foreign = createOfficer(createTestPlayer("foreignchief", 30).getId(), "idle", 100, 100, 100);
        assertEquals(false, officers.appoint(playerId, foreign.getId(), "commander_in_chief").get("success"));
        assertThrows(IllegalArgumentException.class, () -> officers.appoint(playerId, chief.getId(), "injected_role"));
        createArmyUnit(playerId, "infantry", 100);
        assertEquals("请先解除司令官任命再出征", assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(playerId,
                new DispatchRequest("wild", wild.getId(), "conquer", Map.of("infantry", 10), chief.getId(), null))).getMessage());
        March march = outbound(false);
        march.setCommanderId(chief.getId()); marchRepository.saveAndFlush(march);
        assertThrows(IllegalArgumentException.class, () -> officers.appoint(playerId, chief.getId(), "commander_in_chief"));
    }

    @Test void citySelectionNeverChangesExplicitCityChiefAndDemolitionPausesBonus() {
        createBuilding(playerId, "headquarters", 1);
        Officer mainChief = createOfficer(playerId, "commander_in_chief", 100, 100, 100);
        try (var ignored = scope.enter(playerId, 1)) {
            Building branchHq = createBuilding(playerId, "headquarters", 1);
            branchHq.setCitySlot(1); buildingRepository.save(branchHq);
            Officer branchChief = createOfficer(playerId, "commander_in_chief", 200, 200, 200);
            branchChief.setCitySlot(1); officerRepository.saveAndFlush(branchChief);
            assertEquals(mainChief.getId(), targets.getCommanderInChief(playerId, 0).getId());
            assertEquals(branchChief.getId(), targets.getCommanderInChief(playerId, 1).getId());
            assertEquals(false, officers.appoint(playerId, mainChief.getId(), "commander_in_chief").get("success"));
            buildingRepository.delete(branchHq); buildingRepository.flush();
            assertNull(targets.getCommanderInChief(playerId, 1));
            officers.appoint(playerId, branchChief.getId(), "commander_in_chief");
            assertEquals("idle", branchChief.getRole());
        }
    }

    @Test void wildBattleFreezesEquipmentSetAttributesAndExcludesChiefSkills() {
        createBuilding(playerId, "headquarters", 1);
        Officer chief = createOfficer(playerId, "commander_in_chief", 219, 100, 80, 60);
        chief.setSkills("[{\"id\":\"frenzy\",\"lv\":5},{\"id\":\"blitz\",\"lv\":5}]"); officerRepository.save(chief);
        for (String slot : new String[]{"weapon", "badge", "coat"}) equip(chief, "recruit_military_" + slot);
        Officer leader = createOfficer(playerId, "march", 70, 50, 40, 30);
        leader.setSkills("[{\"id\":\"bulwark\",\"lv\":2}]"); officerRepository.save(leader);
        March march = outbound(true); march.setCommanderId(leader.getId()); marchRepository.saveAndFlush(march);
        marchService.processMarches(playerId, march.getArriveAt() + 1);
        BattleSession session = sessions.findByMarchId(march.getId()).orElseThrow();
        CommandBonus snapshot = CommandBonus.fromJson(session.getAttackerCommandBonus());
        assertEquals(237, snapshot.military()); // 219 + 三件装备各5 + 三件套3。
        assertEquals(103, snapshot.defense());
        assertEquals(70, session.getAttackerCommanderMil());
        assertEquals(Map.of("bulwark", 2), JsonUtil.parseIntMap(session.getAttackerSkills()));
        equipment.deleteAll(equipment.findByPlayerIdAndOfficerId(playerId, chief.getId())); equipment.flush();
        officers.appoint(playerId, chief.getId(), "commander_in_chief");
        marchService.processTimedOutTacticalBattle(session.getId(), session.getRoundDeadlineAt());
        BattleSession resumed = sessions.findById(session.getId()).orElseThrow();
        assertEquals(snapshot, CommandBonus.fromJson(resumed.getAttackerCommandBonus()));
        assertTrue(resumed.getBattleLog().contains("司令官"));
    }

    @Test void automaticWildReportContainsTheChiefSnapshotWithoutChiefSkills() {
        createBuilding(playerId, "headquarters", 1);
        Officer chief = createOfficer(playerId, "commander_in_chief", 150, 120, 100, 80);
        chief.setSkills("[{\"id\":\"frenzy\",\"lv\":5}]"); officerRepository.save(chief);
        March march = outbound(false);
        marchService.processMarches(playerId, march.getArriveAt() + 1);
        Map<String, Object> report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData());
        Map<?, ?> bonuses = (Map<?, ?>) report.get("commandBonuses");
        Map<?, ?> attacker = (Map<?, ?>) bonuses.get("attacker");
        assertEquals(chief.getId().intValue(), ((Number) attacker.get("officerId")).intValue());
        assertEquals(150, attacker.get("military"));
        assertFalse(attacker.containsKey("skills"));
        assertFalse(String.valueOf(report.get("report")).contains("全军冲锋"));
    }

    @ParameterizedTest
    @CsvSource({"military,false", "military,true", "defense,false", "defense,true", "logistics,false", "logistics,true"})
    void scoutBonusesStayWithTheirOwnSideRegardlessOfInitiative(String attribute, boolean defenderFirst) {
        Player defender = createTestPlayer("chief-scout-defender", 30);
        PlayerCity target = branchCity(defender.getId(), 25);
        createBuilding(playerId, "headquarters", 1);
        headquarters(defender.getId(), 1);
        Officer attackerChief = createOfficer(playerId, "commander_in_chief", 0, 0, 0, 0);
        Officer defenderChief = createOfficer(defender.getId(), "commander_in_chief", 0, 0, 0, 0);
        defenderChief.setCitySlot(1); officerRepository.saveAndFlush(defenderChief);
        ArmyUnit garrison = createArmyUnit(defender.getId(), "scout", 1000);
        garrison.setCitySlot(1); armyUnitRepository.saveAndFlush(garrison);
        if (defenderFirst) createTechnology(defender.getId(), "recon_radar", 2);

        Map<String, Object> plain = scoutReport(target, garrison);
        assertEquals(defenderFirst ? "defender" : "attacker", plain.get("firstStrike"));
        setAttribute(attackerChief, attribute, 219);
        Map<String, Object> attackBoost = scoutReport(target, garrison);
        setAttribute(attackerChief, attribute, 0);
        setAttribute(defenderChief, attribute, 219);
        Map<String, Object> defenseBoost = scoutReport(target, garrison);

        // 比较真实战损，覆盖双方先手路径，不能把对方属性串入己方攻击或受击耐久。
        if ("military".equals(attribute)) {
            assertTrue((Integer) attackBoost.get("enemyLost") > (Integer) plain.get("enemyLost"));
            assertTrue((Integer) defenseBoost.get("myLost") > (Integer) plain.get("myLost"));
        } else {
            assertTrue((Integer) attackBoost.get("myLost") < (Integer) plain.get("myLost"));
            assertTrue((Integer) defenseBoost.get("enemyLost") < (Integer) plain.get("enemyLost"));
        }
    }

    @Test void scoutKnowledgeChangesInitiativeWithoutChangingIntelligenceTierOrUsingSkills() {
        headquarters(playerId, 0);
        Officer chief = createOfficer(playerId, "commander_in_chief", 0, 0, 0, 219);
        chief.setCitySlot(0);
        chief.setSkills("[{\"id\":\"blitz\",\"lv\":5}]"); officerRepository.saveAndFlush(chief);
        Player defender = createTestPlayer("chief-scout-speed", 30);
        PlayerCity target = branchCity(defender.getId(), 25);
        headquarters(defender.getId(), 1);
        Officer defenderChief = createOfficer(defender.getId(), "commander_in_chief", 0, 0, 0, 100);
        defenderChief.setCitySlot(1); officerRepository.saveAndFlush(defenderChief);
        ArmyUnit garrison = createArmyUnit(defender.getId(), "scout", 1000);
        garrison.setCitySlot(1); armyUnitRepository.saveAndFlush(garrison);
        createTechnology(defender.getId(), "attack_tech", 3);
        Map<String, Object> boosted = scoutReport(target, garrison);
        assertEquals("attacker", boosted.get("firstStrike"));
        assertEquals(11, boosted.get("attackerInitiative")); // round(9 × 1.219) = 11，移除进攻方+2先手偏置，司令官技能不参与。
        assertEquals(0, boosted.get("reconLevel"));
        assertFalse(boosted.containsKey("techs"));
        chief.setKnowledge(0); officerRepository.saveAndFlush(chief);
        Map<String, Object> plain = scoutReport(target, garrison);
        assertEquals("defender", plain.get("firstStrike"));
        assertEquals(9, plain.get("attackerInitiative"));
    }

    @Test void playerCityBattleUsesSourceAndTargetCitySnapshotsInBothViewerPerspectives() {
        Player attacker = playerRepository.findById(playerId).orElseThrow();
        Player defender = createTestPlayer("chief-city-defender", 30);
        PlayerCity source = branchCity(playerId, 12);
        PlayerCity target = branchCity(defender.getId(), 25);
        headquarters(playerId, 0); headquarters(playerId, 1);
        headquarters(defender.getId(), 0); headquarters(defender.getId(), 1);
        createOfficer(playerId, "commander_in_chief", 10, 10, 10, 10);
        Officer attackChief = createOfficer(playerId, "commander_in_chief", 180, 160, 140, 120);
        attackChief.setCitySlot(1); officerRepository.saveAndFlush(attackChief);
        createOfficer(defender.getId(), "commander_in_chief", 30, 30, 30, 30);
        Officer defenseChief = createOfficer(defender.getId(), "commander_in_chief", 210, 190, 170, 150);
        defenseChief.setCitySlot(1); defenseChief.setSkills("[{\"id\":\"frenzy\",\"lv\":5}]");
        officerRepository.saveAndFlush(defenseChief);
        Officer commander = createOfficer(defender.getId(), "commander", 70, 60, 50, 40);
        commander.setCitySlot(1); commander.setSkills("[{\"id\":\"bulwark\",\"lv\":2}]");
        officerRepository.saveAndFlush(commander);
        ArmyUnit army = createArmyUnit(defender.getId(), "infantry", 100);
        army.setCitySlot(1); armyUnitRepository.saveAndFlush(army);
        long now = System.currentTimeMillis();
        attacker.setWarAgainstId(defender.getId()); attacker.setWarAt(now - 1); attacker.setWarEndAt(now + 3_600_000);
        playerRepository.saveAndFlush(attacker);
        March march = createMarch(playerId, "player", target.getId().toString(), target.getName(),
                source.getX(), source.getY(), target.getX(), target.getY(), Map.of("infantry", 100),
                "plunder", now - 1000, now, false, false);
        march.setCitySlot(1); marchRepository.saveAndFlush(march);
        // 双方正在查看主城，来源分城和被攻击分城仍必须提供各自快照。
        Map<String, Object> attackingView = marchService.getTacticalBattle(playerId, march.getId());
        BattleSession session = sessions.findByMarchId(march.getId()).orElseThrow();
        assertEquals(attackChief.getId(), CommandBonus.fromJson(session.getAttackerCommandBonus()).officerId());
        assertEquals(180, CommandBonus.fromJson(session.getAttackerCommandBonus()).military());
        assertEquals(defenseChief.getId(), CommandBonus.fromJson(session.getDefenderCommandBonus()).officerId());
        assertEquals(210, CommandBonus.fromJson(session.getDefenderCommandBonus()).military());
        assertEquals(commander.getId(), session.getDefenderCommanderId());
        assertEquals(Map.of("bulwark", 2), JsonUtil.parseIntMap(session.getDefenderSkills()));
        Map<String, Object> defendingView = marchService.getTacticalBattle(defender.getId(), march.getId());
        // 双方使用各自分城的常驻加成，但接口不公开敌方内部军官快照。
        assertEquals(CommandBonus.fromJson(session.getAttackerCommandBonus()).toMap(), attackingView.get("attackerCommandBonus"));
        assertEquals(CommandBonus.fromJson(session.getDefenderCommandBonus()).toMap(), defendingView.get("attackerCommandBonus"));
        assertEquals(Map.of(), attackingView.get("defenderCommandBonus"));
        assertEquals(Map.of(), defendingView.get("defenderCommandBonus"));
    }

    private PlayerCity branchCity(Long owner, int x) {
        PlayerCity city = new PlayerCity();
        city.setOwnerId(owner); city.setCitySlot(1); city.setWorldId(wild.getWorldId());
        city.setName("分城"); city.setX(x); city.setY(20); city.setLevel(3);
        return playerCityRepository.saveAndFlush(city);
    }

    private void headquarters(Long owner, int slot) {
        Building building = new Building();
        building.setPlayerId(owner); building.setCitySlot(slot); building.setType("headquarters"); building.setLevel(1);
        buildingRepository.saveAndFlush(building);
    }

    private void setAttribute(Officer chief, String attribute, int value) {
        switch (attribute) {
            case "military" -> chief.setMilitary(value);
            case "defense" -> chief.setDefense(value);
            case "logistics" -> chief.setLogistics(value);
            default -> throw new IllegalArgumentException(attribute);
        }
        officerRepository.saveAndFlush(chief);
    }

    /** 每次恢复守军库存，经真实到达结算对比同一场景的战损，不推进之前的返程行军。 */
    private Map<String, Object> scoutReport(PlayerCity target, ArmyUnit garrison) {
        garrison.setCount(1000); armyUnitRepository.saveAndFlush(garrison);
        long now = System.currentTimeMillis();
        March march = createMarch(playerId, "player", target.getId().toString(), target.getName(),
                10, 10, target.getX(), target.getY(), Map.of("scout", 1000), "scout", now - 1000, now, false, false);
        marchService.processMarches(playerId, now);
        Map<String, Object> report = scoutReportRepository.findByPlayerId(playerId).stream()
                .map(row -> JsonUtil.parseObjMap(row.getData()))
                .filter(data -> ((Number) data.get("marchId")).longValue() == march.getId()).findFirst().orElseThrow();
        marchRepository.deleteById(march.getId()); marchRepository.flush();
        return report;
    }

    private March outbound(boolean manual) {
        long now = System.currentTimeMillis();
        March march = createMarch(playerId, "wild", String.valueOf(wild.getId()), "树林", 10, 10, 15, 15,
                Map.of("infantry", 100), "conquer", now - 1000, now, false, false);
        march.setBattleMode(manual ? "manual" : "auto");
        return marchRepository.saveAndFlush(march);
    }

    private void equip(Officer chief, String key) {
        OfficerEquipmentDef def = OfficerEquipmentDef.ITEMS.get(key);
        OfficerEquipment item = new OfficerEquipment();
        item.setPlayerId(playerId); item.setOfficerId(chief.getId()); item.setItemKey(key);
        item.setSetType(def.setKey()); item.setTier(def.tier()); item.setSlot(def.slot());
        item.setMilitaryBonus(def.military()); item.setDefenseBonus(def.defense());
        item.setLogisticsBonus(def.logistics()); item.setKnowledgeBonus(def.knowledge());
        item.setCreatedAt(0L); item.setEquippedAt(0L); equipment.saveAndFlush(item);
    }

    @Test
    void marchQueueCapLinkedToHeadquartersLevel() {
        // 未建司令部：基础5支队伍
        assertEquals(5, marchService.getMaxMarches(playerId, 0));

        // 1级司令部：6支队伍
        Building hq = createBuilding(playerId, "headquarters", 1);
        assertEquals(6, marchService.getMaxMarches(playerId, 0));

        // 3级司令部：5 + 3 = 8支队伍
        hq.setLevel(3);
        buildingRepository.saveAndFlush(hq);
        assertEquals(8, marchService.getMaxMarches(playerId, 0));

        // 10级司令部：5 + 10 = 15支队伍
        hq.setLevel(10);
        buildingRepository.saveAndFlush(hq);
        assertEquals(15, marchService.getMaxMarches(playerId, 0));

        // 降回1级司令部测试队列上限拦截 (上限 6)
        hq.setLevel(1);
        buildingRepository.saveAndFlush(hq);
        assertEquals(6, marchService.getMaxMarches(playerId, 0));

        // 准备充足兵力与资源
        createArmyUnit(playerId, "infantry", 1000);
        giveResources(playerId, 10000, 10000, 10000, 10000, 10000);

        // 创建5支在途行军
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            createMarch(playerId, "wild", String.valueOf(wild.getId()), "树林", 10, 10, 15, 15,
                    Map.of("infantry", 10), "conquer", now, now + 10000, false, false);
        }
        assertEquals(5, marchRepository.countByPlayerIdAndCitySlot(playerId, 0));

        // 发起第6支出征（正常成功，达到 6/6）
        DispatchRequest req6 = new DispatchRequest("wild", wild.getId(), "conquer", Map.of("infantry", 10), null, null);
        assertNotNull(marchService.createDispatch(playerId, req6));
        assertEquals(6, marchRepository.countByPlayerIdAndCitySlot(playerId, 0));

        // 发起第7支出征（应被拦截抛出异常）
        DispatchRequest req7 = new DispatchRequest("wild", wild.getId(), "conquer", Map.of("infantry", 10), null, null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(playerId, req7));
        assertTrue(ex.getMessage().contains("出征队伍已达上限"));

        // 升级司令部至2级（上限提升为 7），第7支成功出征
        hq.setLevel(2);
        buildingRepository.saveAndFlush(hq);
        assertEquals(7, marchService.getMaxMarches(playerId, 0));
        assertNotNull(marchService.createDispatch(playerId, req7));
        assertEquals(7, marchRepository.countByPlayerIdAndCitySlot(playerId, 0));
    }
}
