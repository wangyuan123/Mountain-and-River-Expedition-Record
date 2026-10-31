package com.wargame.service;

import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 账号装备图鉴与展示成就；已停用的整备、零件任务记录仅留存历史，不参与结算。 */
@Service
@Transactional
public class OfficerEquipmentProgressService {
    public static final Map<String, String> ACHIEVEMENTS = new LinkedHashMap<>();
    static {
        ACHIEVEMENTS.put("first", "初装成军"); ACHIEVEMENTS.put("branches", "四军齐备");
        ACHIEVEMENTS.put("collection", "军械收藏家"); ACHIEVEMENTS.put("marshal", "元帅仪仗");
        ACHIEVEMENTS.put("empire", "帝国军备");
    }
    private final OfficerEquipmentProgressRepository progress;
    private final PlayerRepository players;
    private final PlayerItemRepository items;
    private final OfficerRepository officers;
    private final OfficerEquipmentRepository equipment;

    public OfficerEquipmentProgressService(OfficerEquipmentProgressRepository progress, PlayerRepository players,
            PlayerItemRepository items, OfficerRepository officers, OfficerEquipmentRepository equipment) {
        this.progress = progress; this.players = players; this.items = items; this.officers = officers; this.equipment = equipment;
    }
    public void lockPlayer(Long playerId) {
        players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
    }
    private int get(Long playerId, String key) { return progress.findByPlayerIdAndProgressKey(playerId, key).map(OfficerEquipmentProgress::getAmount).orElse(0); }
    private void put(Long playerId, String key, int amount) {
        var row = progress.findByPlayerIdAndProgressKey(playerId, key).orElseGet(OfficerEquipmentProgress::new);
        row.setPlayerId(playerId); row.setProgressKey(key); row.setAmount(amount); progress.save(row);
    }
    public void recordCollection(Long playerId, Collection<String> keys) {
        lockPlayer(playerId);
        Set<String> existing = new HashSet<>();
        progress.findByPlayerIdAndProgressKeyStartingWith(playerId, "collection/").forEach(r -> existing.add(r.getProgressKey().substring(11)));
        for (String key : keys) if (OfficerEquipmentDef.ITEMS.containsKey(key) && existing.add(key)) put(playerId, "collection/" + key, 1);
    }
    /** 三个账号配装方案仅保存目标装备键；保存不会扣库存或复制实物。 */
    public Map<String, Object> saveLoadout(Long playerId, int index, Long officerId) {
        lockPlayer(playerId);
        if (index < 1 || index > 3 || officerId == null || officers.findById(officerId).filter(o -> playerId.equals(o.getPlayerId())).isEmpty())
            return Map.of("success", false, "message", "无效的军官或配装方案");
        var worn = equipment.findByPlayerIdAndOfficerId(playerId, officerId);
        if (worn.size() != 12 || worn.stream().map(OfficerEquipment::getSlot).distinct().count() != 12
                || worn.stream().anyMatch(e -> !OfficerEquipmentDef.ITEMS.containsKey(e.getItemKey())))
            return Map.of("success", false, "message", "请先穿齐十二件再保存混搭方案");
        String prefix = "loadout/" + index + "/";
        progress.deleteAll(progress.findByPlayerIdAndProgressKeyStartingWith(playerId, prefix));
        progress.flush();
        for (var e : worn) put(playerId, prefix + e.getItemKey(), 1);
        return Map.of("success", true, "message", "已保存配装方案" + index);
    }
    public List<String> loadout(Long playerId, int index) {
        String prefix = "loadout/" + index + "/";
        return progress.findByPlayerIdAndProgressKeyStartingWith(playerId, prefix).stream().map(OfficerEquipmentProgress::getProgressKey)
                .filter(k -> k.startsWith(prefix)).map(k -> k.substring(prefix.length())).toList();
    }

    /** 旧版日常和周常已下线，兼容请求不再发放已取消的材料。 */
    public Map<String, Object> claim(Long playerId, String task) {
        return Map.of("success", false, "message", "装备整备及军备零件任务已取消");
    }

    /** 图鉴与成就只记录已实际发生的持有和穿戴，展示奖励不额外改变属性。 */
    @Transactional(readOnly = true)
    public Map<String, Object> state(Long playerId) { return buildState(playerId, false); }

    /** 仅在实际军备变更的事务内固化成就；普通状态查询保持只读，不申请写锁。 */
    public void synchronize(Long playerId) { lockPlayer(playerId); buildState(playerId, true); }

    private Map<String, Object> buildState(Long playerId, boolean record) {
        var owned = items.findByPlayerId(playerId).stream().filter(i -> i.getCount() > 0).map(PlayerItem::getItemKey).toList();
        var worn = equipment.findByPlayerId(playerId);
        Set<String> known = new LinkedHashSet<>(owned); worn.forEach(e -> known.add(e.getItemKey())); if (record) recordCollection(playerId, known);
        var all = progress.displayRows(playerId);
        Set<String> collection = new LinkedHashSet<>();
        Map<String, Integer> values = new HashMap<>(); all.forEach(r -> values.put(r.getProgressKey(), r.getAmount()));
        values.keySet().stream().filter(k -> k.startsWith("collection/")).forEach(k -> collection.add(k.substring(11)));
        known.stream().filter(OfficerEquipmentDef.ITEMS::containsKey).forEach(collection::add);
        Set<String> fullBranches = new HashSet<>(), marshalBranches = new HashSet<>();
        boolean full = false, marshal = false;
        for (var officer : officers.findByPlayerId(playerId)) {
            Map<String, Set<String>> sets = new HashMap<>();
            for (var e : worn) if (officer.getId().equals(e.getOfficerId())) {
                var d = OfficerEquipmentDef.ITEMS.get(e.getItemKey());
                if (d != null) sets.computeIfAbsent(d.setKey(), k -> new HashSet<>()).add(d.slot());
            }
            for (var entry : sets.entrySet()) if (entry.getValue().size() == 12) {
                var d = OfficerEquipmentDef.setItems(entry.getKey()).get(0); full = true; fullBranches.add(d.branch());
                if (d.tier() == 3) { marshal = true; marshalBranches.add(d.branch()); }
            }
        }
        boolean[] achieved = {full, fullBranches.size() == 4, collection.size() == 288, marshal, marshalBranches.size() == 4};
        int index = 0;
        for (String key : ACHIEVEMENTS.keySet()) if (achieved[index++] && record && get(playerId, "achievement/" + key) == 0) put(playerId, "achievement/" + key, 1);
        List<Map<String, Object>> awards = new ArrayList<>();
        ACHIEVEMENTS.forEach((key, name) -> awards.add(Map.of("id", key, "name", name, "unlocked", get(playerId, "achievement/" + key) > 0)));
        Map<String, List<String>> loadouts = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) loadouts.put(String.valueOf(i), loadout(playerId, i));
        return Map.of("loadouts", loadouts, "collection", collection, "achievements", awards);
    }
}
