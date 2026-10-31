package com.wargame.service;

import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.GameClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 十二部位穿卸与四维计算的唯一入口，详情、战斗和内政均读取相同结果。 */
@Service
public class EquipmentService {
    @org.springframework.beans.factory.annotation.Autowired private InventoryService inventory;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TickService economy;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private GarrisonFoodService garrisonFood;
    private final OfficerRepository officerRepository;
    private final OfficerEquipmentRepository equipmentRepository;
    private final PlayerItemRepository playerItemRepository;
    @org.springframework.beans.factory.annotation.Autowired private OfficerEquipmentProgressService progress;
    @org.springframework.beans.factory.annotation.Autowired private MarchRepository marches;

    public EquipmentService(OfficerRepository officers, OfficerEquipmentRepository equipment, PlayerItemRepository items) {
        this.officerRepository = officers; this.equipmentRepository = equipment; this.playerItemRepository = items;
    }
    private Officer officer(Long player, Long id) {
        return id == null ? null : officerRepository.findById(id).filter(o -> player.equals(o.getPlayerId())).orElse(null);
    }
    private boolean marching(Long player, Officer o) {
        return "march".equals(o.getRole()) || marches.existsByPlayerIdAndCommanderId(player, o.getId());
    }

    @Transactional
    public Map<String, Object> equip(Long playerId, Long officerId, String itemKey) {
        progress.lockPlayer(playerId);
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        var o = officer(playerId, officerId); var def = OfficerEquipmentDef.ITEMS.get(itemKey == null ? "" : itemKey);
        if (o == null) return error("军官不存在");
        if (def == null) return error("无效的装备");
        if (marching(playerId, o)) return error("军官正在行军，无法换装");
        if (Math.max(1, value(o.getLevel())) < def.requiredLevel()) return error("军官等级不足，需要 Lv." + def.requiredLevel());
        var current = equipmentRepository.findByPlayerIdAndOfficerId(playerId, officerId);
        if (current.stream().anyMatch(e -> itemKey.equals(e.getItemKey()))) return success("已穿戴该装备");
        if (count(playerId, itemKey) < 1) return error("装备不在库存中");
        replace(playerId, officerId, def);
        progress.recordCollection(playerId, List.of(itemKey));
        progress.synchronize(playerId);
        return success("已为 " + o.getName() + " 装备 " + def.name());
    }

    /** 校验整套所需库存与等级后再扣减；缺件时整体失败，已有同款不额外消耗。 */
    @Transactional
    public Map<String, Object> equipSet(Long playerId, Long officerId, String setKey) {
        var defs = OfficerEquipmentDef.setItems(setKey);
        return equipLoadout(playerId, officerId, defs.stream().map(OfficerEquipmentDef::key).toList());
    }

    /** 混搭方案与整套使用同一原子穿戴校验，不会从其他军官身上偷取装备。 */
    @Transactional
    public Map<String, Object> equipLoadout(Long playerId, Long officerId, List<String> keys) {
        progress.lockPlayer(playerId);
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        var o = officer(playerId, officerId);
        if (o == null) return error("军官不存在");
        if (keys.size() != 12 || keys.stream().anyMatch(k -> !OfficerEquipmentDef.ITEMS.containsKey(k))) return error("无效的十二件配装");
        var defs = keys.stream().map(OfficerEquipmentDef.ITEMS::get).toList();
        if (defs.stream().map(OfficerEquipmentDef::slot).distinct().count() != 12) return error("配装部位重复或缺失");
        if (marching(playerId, o)) return error("军官正在行军，无法换装");
        int required = defs.stream().mapToInt(OfficerEquipmentDef::requiredLevel).max().orElse(1);
        if (Math.max(1, value(o.getLevel())) < required) return error("军官等级不足，需要 Lv." + required);
        var current = equipmentRepository.findByPlayerIdAndOfficerId(playerId, officerId);
        List<String> missing = defs.stream().filter(d -> current.stream().noneMatch(e -> d.key().equals(e.getItemKey())) && count(playerId, d.key()) < 1).map(OfficerEquipmentDef::name).toList();
        if (!missing.isEmpty()) return error("缺少装备：" + String.join("、", missing));
        for (var d : defs) if (current.stream().noneMatch(e -> d.key().equals(e.getItemKey()))) replace(playerId, officerId, d);
        progress.recordCollection(playerId, keys);
        progress.synchronize(playerId);
        return success("十二件配装已全部穿戴");
    }

    private int count(Long player, String key) { return playerItemRepository.findByPlayerIdAndItemKey(player, key).map(PlayerItem::getCount).orElse(0); }

    /** 同槽位原地更新以保持唯一约束；库存写入由账号锁串行化，失败抛异常回滚整套操作。 */
    private void replace(Long player, Long officer, OfficerEquipmentDef d) {
        if (playerItemRepository.tryConsume(player, d.key(), 1, GameClock.now()) == 0) throw new IllegalStateException("库存发生变化，请重试");
        // tryConsume 会清理持久化上下文，重新读取槽位实体，避免拿游离旧记录覆盖已保存的更新。
        var old = equipmentRepository.findByPlayerIdAndOfficerId(player, officer).stream().filter(e -> d.slot().equals(e.getSlot())).findFirst().orElse(null);
        if (old != null) addToInventory(player, old.getItemKey());
        var e = old != null ? old : new OfficerEquipment();
        e.setPlayerId(player); e.setOfficerId(officer); e.setItemKey(d.key()); e.setSetType(d.setKey()); e.setTier(d.tier()); e.setSlot(d.slot());
        e.setMilitaryBonus(d.military()); e.setDefenseBonus(d.defense()); e.setLogisticsBonus(d.logistics()); e.setKnowledgeBonus(d.knowledge());
        if (old == null) e.setCreatedAt(GameClock.now()); e.setEquippedAt(GameClock.now()); equipmentRepository.saveAndFlush(e);
    }

    @Transactional
    public Map<String, Object> unequip(Long player, Long id, String slotOrItem) {
        progress.lockPlayer(player);
        economy.settleEconomyBeforeChange(player);
        if (garrisonFood != null) garrisonFood.beforeChange(player);
        var o = officer(player, id);
        if (o == null) return error("军官不存在");
        if (marching(player, o)) return error("军官正在行军，无法卸装");
        var found = equipmentRepository.findByPlayerIdAndOfficerId(player, id).stream()
                .filter(e -> "all".equals(slotOrItem) || e.getSlot().equals(slotOrItem) || e.getItemKey().equals(slotOrItem)).toList();
        if (found.isEmpty()) return error("该装备未穿戴");
        for (var e : found) { addToInventory(player, e.getItemKey()); equipmentRepository.delete(e); }
        equipmentRepository.flush(); return success("已卸下装备并返还仓库");
    }

    /** 兼容旧客户端：整备已取消，拒绝请求且不扣除任何道具或黄金。 */
    public Map<String, Object> prepare(Long player, Long id, String slot, int expectedLevel) {
        return error("装备整备已取消，装备仅提供固定属性加成");
    }

    /** 换装预览复用战斗与内政的属性计算器，按部位去重，不改库存或当前穿戴。 */
    public Map<String, Object> preview(Long playerId, Long officerId, List<String> keys) {
        Officer officer = officer(playerId, officerId);
        if (officer == null) throw new IllegalArgumentException("军官不存在");
        if (keys == null || keys.size() > OfficerEquipmentDef.SLOTS.size()) throw new IllegalArgumentException("装备方案无效");
        var chosen = new ArrayList<OfficerEquipment>();
        var slots = new HashSet<String>();
        boolean allowed = !marching(playerId, officer);
        for (String key : keys) {
            var d = OfficerEquipmentDef.ITEMS.get(key);
            if (d == null || !slots.add(d.slot())) throw new IllegalArgumentException("装备方案含无效或重复部位");
            var e = new OfficerEquipment();
            e.setItemKey(key); e.setSlot(d.slot()); e.setMilitaryBonus(d.military()); e.setDefenseBonus(d.defense());
            e.setLogisticsBonus(d.logistics()); e.setKnowledgeBonus(d.knowledge()); chosen.add(e);
            allowed &= Objects.requireNonNullElse(officer.getLevel(), 1) >= d.requiredLevel();
        }
        var current = attributes(officer); var next = calculateAttributes(officer, chosen);
        var zero = new Officer(); var bonus = calculateAttributes(zero, chosen);
        return Map.of("bonus", Map.of("military", bonus.military(), "defense", bonus.defense(), "logistics", bonus.logistics(), "knowledge", bonus.knowledge()),
                "net", Map.of("military", next.military() - current.military(), "defense", next.defense() - current.defense(),
                        "logistics", next.logistics() - current.logistics(), "knowledge", next.knowledge() - current.knowledge()),
                "allowed", allowed, "setBonuses", next.bonuses());
    }

    public Attributes attributes(Officer officer) {
        if (officer == null) return new Attributes(0, 0, 0, 0, List.of(), List.of());
        int[] a = {value(officer.getMilitary()), value(officer.getDefense()), value(officer.getLogistics()), value(officer.getKnowledge())};
        if (officer.getPlayerId() == null || officer.getId() == null || officer.getId() < 0) return new Attributes(a[0], a[1], a[2], a[3], List.of(), List.of());
        var equipped = equipmentRepository.findByPlayerIdAndOfficerId(officer.getPlayerId(), officer.getId());
        return calculateAttributes(officer, equipped);
    }

    /** 批量估值与战斗共用固定属性、十二部位去重和套装共鸣；历史整备记录不再读取。 */
    public static Attributes calculateAttributes(Officer officer, List<OfficerEquipment> equipped) {
        if (officer == null) return new Attributes(0, 0, 0, 0, List.of(), List.of());
        int[] a = {value(officer.getMilitary()), value(officer.getDefense()), value(officer.getLogistics()), value(officer.getKnowledge())};
        Set<String> used = new HashSet<>(); Map<String, Set<String>> sets = new LinkedHashMap<>();
        for (var e : equipped) {
            if (!used.add(e.getSlot())) continue;
            a[0] += value(e.getMilitaryBonus()); a[1] += value(e.getDefenseBonus()); a[2] += value(e.getLogisticsBonus()); a[3] += value(e.getKnowledgeBonus());
            var d = OfficerEquipmentDef.ITEMS.get(e.getItemKey());
            if (d == null || !d.slot().equals(e.getSlot())) continue;
            sets.computeIfAbsent(d.setKey(), k -> new HashSet<>()).add(d.slot());
        }
        List<Map<String, Object>> bonuses = new ArrayList<>();
        for (var entry : sets.entrySet()) {
            var d = OfficerEquipmentDef.setItems(entry.getKey()).get(0); var t = d.tierConfig(); int count = entry.getValue().size();
            for (int stage = 0; stage < 4; stage++) {
                int threshold = (stage + 1) * 3; if (count < threshold) continue;
                int main = t.path("resonance_increment_main").get(stage).asInt(), all = t.path("resonance_increment_all").get(stage).asInt();
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("setKey", d.setKey()); b.put("setName", d.setName()); b.put("pieces", threshold); b.put("equippedCount", count);
                String branchName = OfficerEquipmentDef.CONFIG.path("branches").get(OfficerEquipmentDef.BRANCHES.indexOf(d.branch())).path("name").asText();
                b.put("description", "集齐" + threshold + "件：" + branchName + "+" + main + (all > 0 ? "，全属性+" + all : ""));
                for (int i = 0; i < 4; i++) { int bonus = all + (OfficerEquipmentDef.BRANCHES.get(i).equals(d.branch()) ? main : 0); a[i] += bonus; b.put(OfficerEquipmentDef.BRANCHES.get(i), bonus); }
                bonuses.add(b);
            }
        }
        return new Attributes(a[0], a[1], a[2], a[3], equipped, bonuses);
    }

    public Map<String, Object> equipmentView(OfficerEquipment e) {
        Map<String, Object> v = new LinkedHashMap<>(); var d = OfficerEquipmentDef.ITEMS.get(e.getItemKey());
        v.put("id", e.getId()); v.put("itemId", e.getItemKey()); v.put("itemKey", e.getItemKey()); v.put("name", d == null ? e.getItemKey() : d.name());
        v.put("setType", e.getSetType()); v.put("slot", e.getSlot()); v.put("tier", e.getTier());
        int[] a = {value(e.getMilitaryBonus()), value(e.getDefenseBonus()), value(e.getLogisticsBonus()), value(e.getKnowledgeBonus())};
        for (int i = 0; i < 4; i++) v.put(OfficerEquipmentDef.BRANCHES.get(i), a[i]); return v;
    }
    private void addToInventory(Long player, String key) {
        inventory.add(player, key, 1);
    }

    private static int value(Integer v) { return v == null ? 0 : v; }
    private static Map<String, Object> error(String m) { return new LinkedHashMap<>(Map.of("success", false, "message", m)); }
    private static Map<String, Object> success(String m) { return new LinkedHashMap<>(Map.of("success", true, "message", m)); }
    public record Attributes(int military, int defense, int logistics, int knowledge, List<OfficerEquipment> equipment, List<Map<String, Object>> bonuses) {}
}
