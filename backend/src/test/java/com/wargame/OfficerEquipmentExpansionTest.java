package com.wargame;

import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OfficerEquipmentExpansionTest extends BaseServiceTest {
    @Autowired EquipmentService gear;
    @Autowired OfficerService officerService;
    @Autowired OfficerEquipmentProgressService progress;
    @Autowired PlayerItemRepository items;
    @Autowired OfficerEquipmentRepository worn;
    @Autowired OfficerEquipmentProgressRepository progressRows;
    @Autowired DepotService depot;
    @Autowired PlayerPowerService power;

    @Test void powerUsesFixedSetBonusesAndIgnoresLegacyPreparation() {
        Long player = createTestPlayer().getId();
        Officer commander = officer(player, 100);
        commander.setRole("commander");
        officerRepository.saveAndFlush(commander);
        ArmyUnit unit = new ArmyUnit();
        unit.setPlayerId(player);
        unit.setType("infantry");
        unit.setCount(100);
        armyUnitRepository.saveAndFlush(unit);
        var before = power.calculate(player).get(player);
        giveSet(player, "marshal_military");
        assertEquals(true, gear.equipSet(player, commander.getId(), "marshal_military").get("success"));
        var equipped = power.calculate(player).get(player);
        assertTrue(equipped.formationPower() > before.formationPower());
        String slot = OfficerEquipmentDef.setItems("marshal_military").get(0).slot();
        legacyProgress(player, "prep/" + commander.getId() + "/" + slot, 5);
        var prepared = power.calculate(player).get(player);
        assertEquals(equipped.formationPower(), prepared.formationPower());
    }

    @Test void mayorKnowledgeFromEquipmentAndSetResonanceAppliesToActualTax() throws Exception {
        Long player = createTestPlayer().getId(); var mayor = officer(player, 100);
        mayor.setRole("mayor"); mayor.setSalary(0); giveSet(player, "marshal_knowledge");
        assertEquals(true, gear.equipSet(player, mayor.getId(), "marshal_knowledge").get("success"));
        createBuilding(player, "house", 1);
        Player owner = playerRepository.findById(player).orElseThrow(); owner.setCivilianPopulation(1200); owner.setTax(30);
        long now = GameClock.now(); owner.setLastTick(now - 3600000);
        giveResources(player, 0, 0, 0, 0, 0);
        int knowledge = gear.attributes(mayor).knowledge();
        assertTrue(knowledge > mayor.getKnowledge());
        try (var ignored = GameClock.use(() -> now)) { tickService.tick(player); }
        assertEquals(Math.round(720 * (1 + knowledge / 100.0)), getResources(player).getGold());
    }

    private Officer officer(Long player, int level) {
        Officer o = new Officer(); o.setPlayerId(player); o.setName("军备测试"); o.setLevel(level); o.setStar(5);
        o.setMilitary(100); o.setDefense(100); o.setLogistics(100); o.setKnowledge(100); o.setRole("idle");
        return officerRepository.saveAndFlush(o);
    }
    private void give(Long player, String key, int amount) {
        var item = items.findByPlayerIdAndItemKey(player, key).orElseGet(PlayerItem::new);
        item.setPlayerId(player); item.setItemKey(key); item.setCount(amount); item.setUpdatedAt(GameClock.now()); items.saveAndFlush(item);
    }
    private void giveSet(Long player, String set) { OfficerEquipmentDef.setItems(set).forEach(d -> give(player, d.key(), 1)); }
    private void legacyProgress(Long player, String key, int amount) {
        var row = new OfficerEquipmentProgress(); row.setPlayerId(player); row.setProgressKey(key); row.setAmount(amount);
        progressRows.saveAndFlush(row);
    }
    private int count(Long player, String key) { return items.findByPlayerIdAndItemKey(player, key).map(PlayerItem::getCount).orElse(0); }

    @Test void everyBoxAwardsExactlyTwelveDistinctSlotsAndCannotBeReused() {
        Long player = createTestPlayer().getId();
        for (var t : OfficerEquipmentDef.TIERS.keySet()) for (var b : OfficerEquipmentDef.BRANCHES) {
            String box = "box_" + t + "_" + b; give(player, box, 1);
            assertEquals(true, depot.useItem(player, box, null, null).get("success"));
            var defs = OfficerEquipmentDef.boxItems(box);
            assertEquals(12, defs.stream().map(OfficerEquipmentDef::slot).distinct().count());
            defs.forEach(d -> assertEquals(1, count(player, d.key())));
            assertEquals(false, depot.useItem(player, box, null, null).get("success"));
        }
        assertEquals(288, ((Collection<?>) progress.state(player).get("collection")).size());
    }

    @Test void allSixTiersAndFourBranchesUseTheDocumentedAttributeBudget() {
        Long player = createTestPlayer().getId(); var o = officer(player, 100);
        for (var tier : OfficerEquipmentDef.TIERS.entrySet()) for (int branch = 0; branch < 4; branch++) {
            String set = tier.getKey() + "_" + OfficerEquipmentDef.BRANCHES.get(branch); giveSet(player, set);
            assertEquals(true, gear.equipSet(player, o.getId(), set).get("success"));
            var a = gear.attributes(o); int[] actual = {a.military(), a.defense(), a.logistics(), a.knowledge()};
            for (int i = 0; i < 4; i++) assertEquals(100 + tier.getValue().path("expected_full_set").path(i == branch ? "main" : "each_secondary").asInt(), actual[i], set);
            assertEquals(4, a.bonuses().size());
        }
    }

    @Test void resonanceThresholdsAndMixedBranchesAreCumulative() {
        Long p = createTestPlayer().getId(); var o = officer(p, 100); giveSet(p, "marshal_military");
        int base = 100;
        var defs = OfficerEquipmentDef.setItems("marshal_military");
        for (int i = 0; i < 12; i++) {
            var d = defs.get(i); assertEquals(true, gear.equip(p, o.getId(), d.key()).get("success")); base += d.military();
            int n = i + 1, stages = n / 3;
            int resonance = stages == 0 ? 0 : 35 + Math.max(0, stages - 1) * 6 + (stages == 4 ? 2 : 0);
            assertEquals(base + resonance, gear.attributes(o).military(), "pieces=" + n);
            assertEquals(stages, gear.attributes(o).bonuses().size());
        }
        giveSet(p, "marshal_defense");
        for (int i = 0; i < 6; i++) gear.equip(p, o.getId(), OfficerEquipmentDef.setItems("marshal_defense").get(i).key());
        var mixed = gear.attributes(o); assertEquals(4, mixed.bonuses().size());
        assertTrue(mixed.bonuses().stream().allMatch(b -> ((Integer) b.get("pieces")) <= 6));
    }

    @Test void levelsOwnershipAndMissingPartsDoNotConsumeAnythingAndReplacementReturnsOldGear() {
        Long p = createTestPlayer().getId(); var o = officer(p, 19); giveSet(p, "sergeant_military");
        assertEquals(false, gear.equipSet(p, o.getId(), "sergeant_military").get("success"));
        assertEquals(1, count(p, "sergeant_military_weapon")); assertTrue(worn.findByPlayerId(p).isEmpty());
        o.setLevel(20); officerRepository.saveAndFlush(o);
        assertEquals(true, gear.equipSet(p, o.getId(), "sergeant_military").get("success"));
        giveSet(p, "recruit_defense"); give(p, "recruit_defense_watch", 0);
        assertEquals(false, gear.equipSet(p, o.getId(), "recruit_defense").get("success"));
        assertTrue(worn.findByPlayerId(p).stream().allMatch(e -> e.getSetType().equals("sergeant_military")));
        give(p, "recruit_defense_watch", 1);
        assertEquals(true, gear.equipSet(p, o.getId(), "recruit_defense").get("success"));
        assertEquals(12, worn.findByPlayerId(p).size()); assertEquals(1, count(p, "sergeant_military_weapon"));
        assertEquals(false, gear.unequip(createTestPlayer("other", 30).getId(), o.getId(), "all").get("success"));
        assertEquals(true, gear.unequip(p, o.getId(), "all").get("success"));
        assertEquals(1, count(p, "recruit_defense_weapon"));
    }

    @Test void legacyPreparationCannotChangeFixedAttributesOrEquipmentViews() {
        Long p = createTestPlayer().getId(); var o = officer(p, 100);
        giveSet(p, "marshal_military"); gear.equipSet(p, o.getId(), "marshal_military");
        for (String slot : OfficerEquipmentDef.SLOTS) legacyProgress(p, "prep/" + o.getId() + "/" + slot, 5);
        assertEquals(272, gear.attributes(o).military()); assertEquals(122, gear.attributes(o).defense());
        var equipped = worn.findByPlayerId(p).stream().filter(e -> e.getSlot().equals("weapon")).findFirst().orElseThrow();
        var view = gear.equipmentView(equipped);
        assertEquals(30, view.get("military"));
        assertFalse(view.containsKey("preparation")); assertFalse(view.containsKey("effectivePreparation"));
        give(p, "recruit_defense_weapon", 1); gear.equip(p, o.getId(), "recruit_defense_weapon");
        assertEquals(5, gear.equipmentView(worn.findByPlayerId(p).stream().filter(e -> e.getSlot().equals("weapon")).findFirst().orElseThrow()).get("defense"));
        gear.unequip(p, o.getId(), "all"); assertEquals(100, gear.attributes(o).military());
        giveSet(p, "marshal_military"); gear.equipSet(p, o.getId(), "marshal_military");
        assertEquals(272, gear.attributes(o).military());
        assertEquals(gear.attributes(o).defense(), officerService.computeAttributes(p, o.getId()).get("defense"));
    }

    @Test void retiredPreparationAndTaskRequestsNeverChargeOrAwardMaterials() {
        Long p = createTestPlayer().getId(); var o = officer(p, 100);
        giveResources(p, 100, 100, 100, 100, 200000); give(p, "officerPreparationParts", 4000);
        legacyProgress(p, "daily/2026-10-30/train", 100);
        legacyProgress(p, "week/2026-10-26", 15);
        for (int i = 0; i < 2; i++) {
            assertEquals(false, gear.prepare(p, o.getId(), "weapon", 0).get("success"));
            for (String task : List.of("train", "wild", "gather", "develop", "weekly")) assertEquals(false, progress.claim(p, task).get("success"));
        }
        assertEquals(4000, count(p, "officerPreparationParts"));
        assertEquals(200000, resourcesRepository.findByPlayerIdAndCitySlot(p, 0).orElseThrow().getGold());
        assertTrue(progressRows.findByPlayerIdAndProgressKeyStartingWith(p, "prep/").isEmpty());
        var state = progress.state(p);
        for (String key : List.of("parts", "daily", "dailyClaims", "weeklyProgress", "weeklyClaimed")) assertFalse(state.containsKey(key));
        assertFalse(((Map<?, ?>) gameStateService.getGameState(p).get("items")).containsKey("officerPreparationParts"));
        assertFalse(com.wargame.model.constants.ItemDef.ITEMS.containsKey("officerPreparationParts"));
    }

    @Test void marshalAchievementsRequireFixedSetsAndNoPreparation() {
        Long p = createTestPlayer().getId();
        for (String branch : OfficerEquipmentDef.BRANCHES) {
            var o = officer(p, 100); giveSet(p, "marshal_" + branch);
            assertEquals(true, gear.equipSet(p, o.getId(), "marshal_" + branch).get("success"));
        }
        var awards = (List<Map<String, Object>>) progress.state(p).get("achievements");
        assertTrue(awards.stream().filter(a -> List.of("first", "branches", "marshal", "empire").contains(a.get("id"))).allMatch(a -> a.get("unlocked").equals(true)));
        assertTrue(awards.stream().noneMatch(a -> List.of("prepared3", "prepared5").contains(a.get("id"))));
    }

    @Test void presetsDoNotDuplicateEquipmentAndAchievementCollectionPersistsAfterUnequip() {
        Long p = createTestPlayer().getId(); var o = officer(p, 100); giveSet(p, "recruit_military"); gear.equipSet(p, o.getId(), "recruit_military");
        assertEquals(true, progress.saveLoadout(p, 1, o.getId()).get("success"));
        var second = officer(p, 100);
        assertEquals(false, gear.equipLoadout(p, second.getId(), progress.loadout(p, 1)).get("success"));
        var achievements = (List<Map<String, Object>>) progress.state(p).get("achievements");
        assertTrue(achievements.stream().anyMatch(a -> a.get("id").equals("first") && a.get("unlocked").equals(true)));
        gear.unequip(p, o.getId(), "all");
        assertEquals(true, gear.equipLoadout(p, second.getId(), progress.loadout(p, 1)).get("success"));
        assertEquals(12, ((Collection<?>) progress.state(p).get("collection")).size());
        assertEquals(0, count(p, "recruit_military_weapon"));
    }

    @Test void trainingAndConstructionNoLongerCreateMaterialTaskProgress() {
        Long p = createTestPlayer().getId();
        long now = GameClock.now();
        createConstruction(p, "farm", 1, now - 1000, now - 1, 0);
        buildService.completeUpgrade(p, now);
        assertTrue(progressRows.findByPlayerIdAndProgressKeyStartingWith(p, "daily/").isEmpty());
        assertTrue(progressRows.findByPlayerIdAndProgressKeyStartingWith(p, "event/").isEmpty());
        assertEquals(0, count(p, "officerPreparationParts"));
    }
}
