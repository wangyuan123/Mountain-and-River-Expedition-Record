package com.wargame.service;

import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.entity.Officer;
import com.wargame.model.entity.OfficerEquipment;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.OfficerEquipmentRepository;
import com.wargame.repository.OfficerRepository;
import com.wargame.repository.PlayerItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 军官装备穿戴与动态属性计算。 */
@Service
public class EquipmentService {
    private final OfficerRepository officerRepository;
    private final OfficerEquipmentRepository equipmentRepository;
    private final PlayerItemRepository playerItemRepository;

    public EquipmentService(OfficerRepository officerRepository, OfficerEquipmentRepository equipmentRepository,
                            PlayerItemRepository playerItemRepository) {
        this.officerRepository = officerRepository;
        this.equipmentRepository = equipmentRepository;
        this.playerItemRepository = playerItemRepository;
    }

    @Transactional
    public Map<String, Object> equip(Long playerId, Long officerId, String itemKey) {
        Officer officer = officerRepository.findById(officerId).filter(o -> playerId.equals(o.getPlayerId())).orElse(null);
        OfficerEquipmentDef def = OfficerEquipmentDef.ITEMS.get(itemKey);
        if (officer == null) return error("军官不存在");
        if (def == null) return error("无效的装备");
        if ((officer.getLevel() == null ? 1 : officer.getLevel()) < def.requiredLevel()) return error("军官等级不足，需要 Lv." + def.requiredLevel());
        if (playerItemRepository.tryConsume(playerId, itemKey, 1, System.currentTimeMillis()) == 0) return error("装备不在库存中");

        equipmentRepository.findByPlayerIdAndOfficerId(playerId, officerId).stream()
                .filter(e -> def.slot().equals(e.getSlot())).forEach(old -> {
                    addToInventory(playerId, old.getItemKey());
                    equipmentRepository.delete(old);
                });
        OfficerEquipment equipment = new OfficerEquipment();
        equipment.setPlayerId(playerId); equipment.setOfficerId(officerId); equipment.setItemKey(def.key());
        equipment.setSetType(def.setKey()); equipment.setTier(def.tier()); equipment.setSlot(def.slot());
        equipment.setMilitaryBonus(def.military()); equipment.setDefenseBonus(def.defense()); equipment.setLogisticsBonus(def.logistics()); equipment.setKnowledgeBonus(def.knowledge());
        equipment.setCreatedAt(System.currentTimeMillis()); equipment.setEquippedAt(System.currentTimeMillis());
        equipmentRepository.save(equipment);
        return success("已为 " + officer.getName() + " 装备 " + def.name());
    }

    @Transactional
    public Map<String, Object> unequip(Long playerId, Long officerId, String slot) {
        Officer officer = officerRepository.findById(officerId).filter(o -> playerId.equals(o.getPlayerId())).orElse(null);
        if (officer == null) return error("军官不存在");
        OfficerEquipment equipment = equipmentRepository.findByPlayerIdAndOfficerId(playerId, officerId).stream()
                .filter(e -> slot.equals(e.getSlot())).findFirst().orElse(null);
        if (equipment == null) return error("该部位未装备");
        addToInventory(playerId, equipment.getItemKey());
        equipmentRepository.delete(equipment);
        return success("已卸下" + equipment.getItemKey());
    }

    public Attributes attributes(Officer officer) {
        List<OfficerEquipment> equipped = equipmentRepository.findByPlayerIdAndOfficerId(officer.getPlayerId(), officer.getId());
        int military = value(officer.getMilitary()), defense = value(officer.getDefense()), logistics = value(officer.getLogistics()), knowledge = value(officer.getKnowledge());
        Map<String, List<OfficerEquipment>> sets = new HashMap<>();
        for (OfficerEquipment e : equipped) {
            military += value(e.getMilitaryBonus()); defense += value(e.getDefenseBonus()); logistics += value(e.getLogisticsBonus()); knowledge += value(e.getKnowledgeBonus());
            sets.computeIfAbsent(e.getSetType(), key -> new ArrayList<>()).add(e);
        }
        List<Map<String, Object>> bonuses = new ArrayList<>();
        for (Map.Entry<String, List<OfficerEquipment>> entry : sets.entrySet()) {
            if (entry.getValue().size() != 3) continue;
            OfficerEquipmentDef def = OfficerEquipmentDef.ITEMS.get(entry.getValue().get(0).getItemKey());
            if (def == null) continue;
            int setMain = def.tier() == 1 ? 3 : def.tier() == 2 ? 15 : 30;
            int all = def.tier() == 3 ? 5 : 0;
            if (def.branch().equals("military")) military += setMain;
            if (def.branch().equals("defense")) defense += setMain;
            if (def.branch().equals("logistics")) logistics += setMain;
            if (def.branch().equals("knowledge")) knowledge += setMain;
            military += all; defense += all; logistics += all; knowledge += all;
            Map<String, Object> bonus = new LinkedHashMap<>();
            bonus.put("setName", def.setName()); bonus.put("description", "集齐3件：" + branchName(def.branch()) + "+" + setMain + (all > 0 ? "，全属性+" + all : ""));
            bonus.put("military", def.branch().equals("military") ? setMain + all : all);
            bonus.put("defense", def.branch().equals("defense") ? setMain + all : all);
            bonus.put("logistics", def.branch().equals("logistics") ? setMain + all : all);
            bonus.put("knowledge", def.branch().equals("knowledge") ? setMain + all : all);
            bonuses.add(bonus);
        }
        return new Attributes(military, defense, logistics, knowledge, equipped, bonuses);
    }

    private void addToInventory(Long playerId, String itemKey) {
        long now = System.currentTimeMillis();
        playerItemRepository.findByPlayerIdAndItemKey(playerId, itemKey).ifPresentOrElse(item -> {
            item.setCount(item.getCount() + 1); item.setUpdatedAt(now); playerItemRepository.save(item);
        }, () -> { PlayerItem item = new PlayerItem(); item.setPlayerId(playerId); item.setItemKey(itemKey); item.setCount(1); item.setUpdatedAt(now); playerItemRepository.save(item); });
    }
    private static int value(Integer value) { return value == null ? 0 : value; }
    private static String branchName(String branch) { return switch (branch) { case "military" -> "军事"; case "defense" -> "防御"; case "logistics" -> "后勤"; default -> "学识"; }; }
    private static Map<String, Object> error(String message) { Map<String, Object> r = new LinkedHashMap<>(); r.put("success", false); r.put("message", message); return r; }
    private static Map<String, Object> success(String message) { Map<String, Object> r = new LinkedHashMap<>(); r.put("success", true); r.put("message", message); return r; }
    public record Attributes(int military, int defense, int logistics, int knowledge, List<OfficerEquipment> equipment, List<Map<String, Object>> bonuses) {}
}
