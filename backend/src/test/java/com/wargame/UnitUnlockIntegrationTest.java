package com.wargame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.service.quest.QuestCatalog;
import com.wargame.service.quest.QuestService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 对照设计逐项破坏条件，并验证新获取入口的原子性与旧资产兼容。 */
class UnitUnlockIntegrationTest extends BaseServiceTest {
    @Autowired UnitUnlockService unlocks;
    @Autowired ArmyService army;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired PlayerItemRepository items;
    @Autowired MailService mail;
    @Autowired MailRepository mails;
    @Autowired PrisonerService prisoners;
    @Autowired PrisonerUnitRepository captives;
    @Autowired BattleSettlementRepository settlements;
    @Autowired CityScope scope;
    @Autowired WorldTerrainService terrain;
    @Autowired QuestService quests;
    @Autowired PlayerQuestRepository questRows;
    @Autowired PlayerQuestStageRepository stages;
    @Autowired TechResearchQueueRepository research;

    private PlayerCity city(Long player, int slot, boolean legacy) {
        WorldMap world = worldMapRepository.findAll().stream().findFirst().orElseGet(this::createTestWorld);
        world.setTerrainData("0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE)); worldMapRepository.save(world);
        PlayerCity c = new PlayerCity(); c.setWorldId(world.getId()); c.setOwnerId(player); c.setCitySlot(slot);
        c.setName("测试城" + slot); c.setX(10 + slot); c.setY(10); c.setLevel(1); c.setLegacyNaval(legacy);
        return playerCityRepository.saveAndFlush(c);
    }

    @Test void everyDesignConditionIsRequiredBeforeAnyRecruitmentPayment() throws Exception {
        long fixedNow = com.wargame.util.GameClock.now();
        // 只验证准入失败不扣人口，固定时钟以排除测试耗时产生的自然人口变化。
        try (var clock = com.wargame.util.GameClock.use(() -> fixedNow)) {
        JsonNode design = new ObjectMapper().readTree(java.nio.file.Path.of("../docs/balance/unit-unlock-design-20261020.json").toFile());
        Player p = createTestPlayer(); p.setCivilianPopulation(10000); p.setLastTick(fixedNow); playerRepository.save(p); city(p.getId(), 0, true);
        giveResources(p.getId(), 1000000, 1000000, 1000000, 1000000, 1000000);
        Map<String, Building> buildings = new HashMap<>(); Map<String, Technology> tech = new HashMap<>();
        for (JsonNode unit : design.path("units")) {
            unit.path("buildings").fieldNames().forEachRemaining(id -> buildings.computeIfAbsent(id, k -> createBuilding(p.getId(), k, 10)));
            unit.path("technologies").fieldNames().forEachRemaining(id -> tech.computeIfAbsent(id, k -> createTechnology(p.getId(), k, 10)));
        }
        assertEquals(17, unlocks.all(p.getId(), 0).size());
        for (JsonNode unit : design.path("units")) {
            String id = unit.path("id").asText();
            unit.path("buildings").fields().forEachRemaining(e -> {
                buildings.get(e.getKey()).setLevel(e.getValue().asInt()); buildingRepository.saveAndFlush(buildings.get(e.getKey()));
            });
            unit.path("technologies").fields().forEachRemaining(e -> {
                tech.get(e.getKey()).setLevel(e.getValue().asInt()); technologyRepository.saveAndFlush(tech.get(e.getKey()));
            });
            var result = unlocks.evaluate(p.getId(), 0, id, true); assertTrue(result.eligible(), id);
            assertEquals(design.path("version").asText(), result.ruleVersion());
            for (String kind : List.of("buildings", "technologies")) {
                var fields = unit.path(kind).fields();
                while (fields.hasNext()) {
                    var condition = fields.next(); String key = condition.getKey(); int level = condition.getValue().asInt();
                    if (kind.equals("buildings")) { buildings.get(key).setLevel(level - 1); buildingRepository.saveAndFlush(buildings.get(key)); }
                    else { tech.get(key).setLevel(level - 1); technologyRepository.saveAndFlush(tech.get(key)); }
                    var before = JsonUtil.parseObjMap(JsonUtil.toJson(getResources(p.getId()))); int civilians = p.getCivilianPopulation();
                    var denied = army.recruit(p.getId(), id, 1);
                    assertEquals(false, denied.get("success"), id + "/" + key);
                    var missing = ((UnitUnlockService.Result) denied.get("unlock")).missing();
                    assertEquals(List.of(key), missing.stream().map(r -> r.get("id")).toList());
                    var after = JsonUtil.parseObjMap(JsonUtil.toJson(getResources(p.getId())));
                    // 经济按请求时刻正常结算，失败招募不得扣资源；不比较乐观锁版本或不足一单位的自然产出尾数。
                    for (String resource : List.of("food", "steel", "oil", "rare", "gold", "diamond")) assertEquals(before.get(resource), after.get(resource), resource);
                    assertEquals(civilians, p.getCivilianPopulation());
                    if (kind.equals("buildings")) { buildings.get(key).setLevel(level); buildingRepository.saveAndFlush(buildings.get(key)); }
                    else { tech.get(key).setLevel(level); technologyRepository.saveAndFlush(tech.get(key)); }
                }
            }
            var accepted = army.recruit(p.getId(), id, 1); assertEquals(true, accepted.get("success"), id);
            var queue = queues.findById((Long) accepted.get("queueId")).orElseThrow();
            assertEquals(unit.path("productionBuilding").asText(), queue.getBuildingType());
            assertEquals(result.ruleVersion(), queue.getUnlockRuleVersion()); assertTrue(queue.getUnlockRequirements().contains("requirements"));
        }
        var carrier = unlocks.evaluate(p.getId(), 0, "carrier", false);
        assertEquals(Set.of("nav_engine", "weapon_range", "fire_density", "log_food", "air_engine"), carrier.requirements().stream()
                .filter(r -> r.get("kind").equals("technology")).map(r -> (String) r.get("id")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(carrier.requirements().stream().filter(r -> !r.get("kind").equals("geography")).allMatch(r -> r.get("required").equals(10)));
            }
    }

    @Test void completedEmpireResearchDoesNotReplaceLocalFacilitiesOrCountPendingWork() {
        Player p = createTestPlayer();
        Building a = createBuilding(p.getId(), "factory", 1), b = createBuilding(p.getId(), "factory", 1); b.setSlot(1); buildingRepository.save(b);
        var job = createConstruction(p.getId(), "factory", 2, 1L, Long.MAX_VALUE, 0);
        assertFalse(unlocks.evaluate(p.getId(), 0, "recon_car", true).eligible());
        var t = createTechnology(p.getId(), "recon_level", 0);
        TechResearchQueue q = new TechResearchQueue(); q.setPlayerId(p.getId()); q.setTechType("recon_level"); q.setTargetLevel(1);
        q.setStartedAt(1L); q.setFinishesAt(Long.MAX_VALUE); q.setDurationSeconds(180); research.save(q);
        a.setLevel(2); buildingRepository.saveAndFlush(a);
        assertFalse(unlocks.evaluate(p.getId(), 0, "recon_car", true).eligible(), "在研不等于完成");
        t.setLevel(1); technologyRepository.saveAndFlush(t);
        assertTrue(unlocks.evaluate(p.getId(), 0, "recon_car", true).eligible());
        job.setTargetLevel(1); constructionRepository.saveAndFlush(job);
        assertFalse(unlocks.evaluate(p.getId(), 0, "recon_car", true).eligible(), "降级立即预扣");
        constructionRepository.delete(job); constructionRepository.flush();
        assertTrue(unlocks.evaluate(p.getId(), 0, "recon_car", true).eligible());
        assertFalse(unlocks.evaluate(p.getId(), 1, "recon_car", true).eligible(), "主城设施不能代替分城");
        Building local = createBuilding(p.getId(), "factory", 2); local.setCitySlot(1); buildingRepository.saveAndFlush(local);
        assertTrue(unlocks.evaluate(p.getId(), 1, "recon_car", true).eligible(), "账号科技全城共享");
    }

    @Test void navalLegacyOnlyWaivesGeographyAndNeverBuildingOrTechnologyLevels() {
        Player p = createTestPlayer(); PlayerCity c = city(p.getId(), 0, false);
        createBuilding(p.getId(), "shipyard", 6); createBuilding(p.getId(), "port", 6);
        createTechnology(p.getId(), "nav_engine", 5); createTechnology(p.getId(), "weapon_range", 4); createTechnology(p.getId(), "log_food", 4);
        assertEquals(List.of("geography"), unlocks.evaluate(p.getId(), 0, "destroyer", true).missing().stream().map(r -> r.get("kind")).toList());
        c.setLegacyNaval(true); playerCityRepository.saveAndFlush(c);
        assertTrue(unlocks.evaluate(p.getId(), 0, "destroyer", true).eligible());
        assertFalse(unlocks.evaluate(p.getId(), 0, "battleship", true).eligible());
        c.setLegacyNaval(false); playerCityRepository.saveAndFlush(c);
        WorldMap world = worldMapRepository.findById(c.getWorldId()).orElseThrow();
        String readyMask = terrain.ensure();
        StringBuilder mask = new StringBuilder(readyMask);
        // 城市占用2x2格，海水需从占地外连续连到世界边界，才能形成真实海岸。
        for (int x = c.getX() + 2; x < WorldTerrainService.SIZE; x++) {
            mask.setCharAt(c.getY() * WorldTerrainService.SIZE + x, '1');
        }
        world.setTerrainData(mask.toString()); worldMapRepository.saveAndFlush(world);
        assertTrue(unlocks.evaluate(p.getId(), 0, "destroyer", true).eligible(), "真实沿海也可准入");
    }

    @Test void captiveRecruitmentRequiresEliteTechnologyButReleaseDoesNot() {
        Player p = createTestPlayer(); PlayerCity c = city(p.getId(), 0, false);
        giveResources(p.getId(), 100000, 100000, 100000, 100000, 100000);
        createBuilding(p.getId(), "factory", 10);
        Technology training = createTechnology(p.getId(), "log_train", 9);
        createTechnology(p.getId(), "attack_tech", 9); createTechnology(p.getId(), "cmd_hp", 9);
        PrisonerUnit captive = new PrisonerUnit(); captive.setPlayerId(p.getId()); captive.setCityId(c.getId()); captive.setCitySlot(0);
        captive.setSettlementId(1L); captive.setSourceId("elite-test"); captive.setOriginalOwnerId(999L);
        captive.setType("special"); captive.setOriginalCount(3); captive.setCount(3); captive.setCreatedAt(1L);
        captives.saveAndFlush(captive);
        assertEquals(List.of("log_train"), unlocks.evaluate(p.getId(), 0, "special", true).missing().stream().map(r -> r.get("id")).toList());
        assertThrows(IllegalArgumentException.class, () -> prisoners.operate(p.getId(), "recruit", "special", 1, "elite-denied-123"));
        assertEquals(100000, getResources(p.getId()).getGold());
        assertEquals(3, captives.findById(captive.getId()).orElseThrow().getCount());
        assertTrue(armyUnitRepository.findByPlayerId(p.getId()).isEmpty());
        assertEquals(true, prisoners.operate(p.getId(), "release", "special", 1, "elite-release-123").get("success"));
        assertEquals(100000, getResources(p.getId()).getGold());
        training.setLevel(10); technologyRepository.saveAndFlush(training);
        assertEquals(true, prisoners.operate(p.getId(), "recruit", "special", 1, "elite-success-123").get("success"));
        assertEquals(1, armyUnitRepository.findByPlayerIdAndType(p.getId(), "special").get(0).getCount());
        assertEquals(1, captives.findById(captive.getId()).orElseThrow().getCount());
    }

    @Test void lockedRewardsPreserveResourcesAndOldPaidArmyOrdersStillComplete() {
        Player p = createTestPlayer(); createBuilding(p.getId(), "factory", 1);
        giveResources(p.getId(), 100000, 100000, 100000, 100000, 100000);
        Mail letter = mail.sendSystem(p.getId(), "系统", "reward", "赠兵", "待领取", List.of(Map.of("type", "gold", "qty", 100), Map.of("type", "recon_car", "qty", 2)));
        assertEquals(false, mail.claimAttach(p.getId(), letter.getId()).get("success"));
        assertFalse(mails.findById(letter.getId()).orElseThrow().getIsClaimed()); assertEquals(100000, getResources(p.getId()).getGold());
        createBuilding(p.getId(), "factory", 10); createTechnology(p.getId(), "recon_level", 1);
        assertEquals(true, mail.claimAttach(p.getId(), letter.getId()).get("success"));
        assertEquals(100100, getResources(p.getId()).getGold());
        assertThrows(IllegalArgumentException.class, () -> mail.claimAttach(p.getId(), letter.getId()));
        ArmyProductionQueue old = new ArmyProductionQueue(); old.setPlayerId(p.getId()); old.setUnitType("carrier"); old.setBuildingType("port");
        old.setUnitCount(1); old.setStartedAt(1L); old.setFinishesAt(2L); old.setDurationSeconds(1); old.setSpeedMultiplier(1.0); queues.save(old);
        army.completeProduction(p.getId(), System.currentTimeMillis());
        assertEquals(1, armyUnitRepository.findByPlayerIdAndType(p.getId(), "carrier").get(0).getCount());
    }

    @Test void navyQuestMovesWithoutRevokingHistoricCompletionOrCountingOtherShips() {
        assertEquals("stage5", QuestCatalog.findQuest("q3_2").chapterId());
        assertEquals("q3_1", QuestCatalog.findQuest("q3_3").requires());
        assertEquals("q3_2", QuestCatalog.findQuest("q5_3").requires());
        var chapter = QuestCatalog.findChapter("stage5").quests().stream().map(QuestCatalog.Quest::id).toList();
        assertEquals(chapter.indexOf("q5_2") + 1, chapter.indexOf("q3_2"));
        Player p = createTestPlayer(); quests.getPlayerQuests(p.getId());
        for (String id : List.of("stage1", "stage2", "stage3", "stage4")) {
            var row = stages.findByPlayerIdAndStageId(p.getId(), id).orElseThrow(); row.setStatus("claimed"); stages.save(row);
        }
        createArmyUnit(p.getId(), "sub", 100);
        var view = quests.getPlayerQuests(p.getId());
        assertEquals(0, questRows.findByPlayerIdAndQuestId(p.getId(), "q3_2").orElseThrow().getProgress());
        createArmyUnit(p.getId(), "destroyer", 20); quests.onEvent(p.getId(), "ARMY_TOTAL");
        assertEquals("completed", questRows.findByPlayerIdAndQuestId(p.getId(), "q3_2").orElseThrow().getStatus());
        var historic = stages.findByPlayerIdAndStageId(p.getId(), "stage5").orElseThrow(); historic.setStatus("completed"); stages.save(historic);
        assertEquals("completed", quests.getPlayerQuests(p.getId()).get(4).get("status"));
        assertEquals(true, quests.claimStage(p.getId(), "stage5").get("success"));
        assertEquals(false, quests.claimStage(p.getId(), "stage5").get("success"));
        assertEquals(1, items.findByPlayerIdAndItemKey(p.getId(), "stage_gift_5").orElseThrow().getCount());
    }
}
