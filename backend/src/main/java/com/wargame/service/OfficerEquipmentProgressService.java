package com.wargame.service;

import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.GameClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 军备任务与培养共用账号锁；事件来源仅为实际结算，不能由客户端提交进度。 */
@Service
@Transactional
public class OfficerEquipmentProgressService {
    public static final String PARTS = "officerPreparationParts";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Map<String, String> TASKS = Map.of("train", "完成训练100名兵员", "wild", "野地战斗胜利一次",
            "gather", "采集资源返城一次", "develop", "完成建筑或科技升级一次");
    public static final Map<String, String> ACHIEVEMENTS = new LinkedHashMap<>();
    static {
        ACHIEVEMENTS.put("first", "初装成军"); ACHIEVEMENTS.put("branches", "四军齐备");
        ACHIEVEMENTS.put("collection", "军械收藏家"); ACHIEVEMENTS.put("prepared3", "精装军官");
        ACHIEVEMENTS.put("prepared5", "王牌整备"); ACHIEVEMENTS.put("marshal", "元帅仪仗");
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
    public Map<String, Integer> preparation(Long playerId, Long officerId) {
        String prefix = "prep/" + officerId + "/";
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var row : progress.findByPlayerIdAndProgressKeyStartingWith(playerId, prefix)) result.put(row.getProgressKey().substring(prefix.length()), row.getAmount());
        return result;
    }
    public void setPreparation(Long playerId, Long officerId, String slot, int level) { put(playerId, "prep/" + officerId + "/" + slot, level); }

    /**
     * 军官转籍时同步转移部位整备；图鉴、任务、成就和账号配装方案不随军官转移。
     * 调用方须已锁定双方账号。兼容历史遗留的同键记录，保留较高培养等级并移除原归属。
     */
    public void transferPreparation(Long formerOwner, Long recipient, Long officerId) {
        if (formerOwner.equals(recipient)) return;
        for (var row : progress.findByPlayerIdAndProgressKeyStartingWith(formerOwner, "prep/" + officerId + "/")) {
            var existing = progress.findByPlayerIdAndProgressKey(recipient, row.getProgressKey()).orElse(null);
            if (existing == null) {
                row.setPlayerId(recipient);
                progress.save(row);
            } else {
                existing.setAmount(Math.max(existing.getAmount(), row.getAmount()));
                progress.save(existing);
                progress.delete(row);
            }
        }
    }
    public void recordCollection(Long playerId, Collection<String> keys) {
        lockPlayer(playerId);
        Set<String> existing = new HashSet<>();
        progress.findByPlayerIdAndProgressKeyStartingWith(playerId, "collection/").forEach(r -> existing.add(r.getProgressKey().substring(11)));
        for (String key : keys) if (OfficerEquipmentDef.ITEMS.containsKey(key) && existing.add(key)) put(playerId, "collection/" + key, 1);
    }
    public void addParts(Long playerId, int count) {
        var item = items.findByPlayerIdAndItemKey(playerId, PARTS).orElseGet(PlayerItem::new);
        item.setPlayerId(playerId); item.setItemKey(PARTS); item.setCount(Objects.requireNonNullElse(item.getCount(), 0) + count);
        item.setUpdatedAt(GameClock.now()); items.save(item);
    }
    private LocalDate today() { return Instant.ofEpochMilli(GameClock.now()).atZone(ZONE).toLocalDate(); }
    private String week() { return today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(); }

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

    /** 每个持久化队列/行军/升级事件只推进一次；回滚时去重标记与奖励进度一起回滚。 */
    public void event(Long playerId, String task, String eventId, int delta) {
        if (!TASKS.containsKey(task) || delta <= 0 || eventId == null) return;
        lockPlayer(playerId);
        String marker = "event/" + task + "/" + eventId;
        if (get(playerId, marker) > 0) return;
        put(playerId, marker, 1);
        String daily = "daily/" + today() + "/" + task;
        put(playerId, daily, Math.min(task.equals("train") ? 100 : 1, get(playerId, daily) + delta));
    }

    /** 日常每日最多三项，周常只计已经领取的日常；重复请求不会重复发放零件。 */
    public Map<String, Object> claim(Long playerId, String task) {
        lockPlayer(playerId);
        if (task == null) return Map.of("success", false, "message", "无效的军备任务");
        String weekKey = "week/" + week();
        if ("weekly".equals(task)) {
            if (get(playerId, weekKey) < 15 || get(playerId, weekKey + "/claimed") > 0) return Map.of("success", false, "message", "周常尚未完成或已领取");
            put(playerId, weekKey + "/claimed", 1); addParts(playerId, 240);
        } else {
            if (!TASKS.containsKey(task)) return Map.of("success", false, "message", "无效的军备任务");
            String base = "daily/" + today(), key = base + "/" + task;
            int required = task.equals("train") ? 100 : 1;
            if (get(playerId, key) < required || get(playerId, key + "/claimed") > 0 || get(playerId, base + "/claims") >= 3)
                return Map.of("success", false, "message", "任务未完成、已领取或已达每日三项上限");
            put(playerId, key + "/claimed", 1); put(playerId, base + "/claims", get(playerId, base + "/claims") + 1);
            put(playerId, weekKey, get(playerId, weekKey) + 1); addParts(playerId, 40);
        }
        return Map.of("success", true, "message", "军备零件已入仓");
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
        var all = progress.displayRows(playerId, "daily/" + today() + "/%", "week/" + week() + "%");
        Set<String> collection = new LinkedHashSet<>();
        Map<String, Integer> values = new HashMap<>(); all.forEach(r -> values.put(r.getProgressKey(), r.getAmount()));
        values.keySet().stream().filter(k -> k.startsWith("collection/")).forEach(k -> collection.add(k.substring(11)));
        known.stream().filter(OfficerEquipmentDef.ITEMS::containsKey).forEach(collection::add);
        Set<String> fullBranches = new HashSet<>(), marshalBranches = new HashSet<>();
        boolean full = false, plus3 = false, plus5 = false, marshal = false;
        for (var officer : officers.findByPlayerId(playerId)) {
            Map<String, Set<String>> sets = new HashMap<>();
            for (var e : worn) if (officer.getId().equals(e.getOfficerId())) {
                var d = OfficerEquipmentDef.ITEMS.get(e.getItemKey());
                if (d != null) sets.computeIfAbsent(d.setKey(), k -> new HashSet<>()).add(d.slot());
            }
            boolean p3 = true, p5 = true;
            for (String slot : OfficerEquipmentDef.SLOTS) {
                int lv = values.getOrDefault("prep/" + officer.getId() + "/" + slot, 0); p3 &= lv >= 3; p5 &= lv >= 5;
            }
            plus3 |= p3; plus5 |= p5;
            for (var entry : sets.entrySet()) if (entry.getValue().size() == 12) {
                var d = OfficerEquipmentDef.setItems(entry.getKey()).get(0); full = true; fullBranches.add(d.branch());
                if (d.tier() == 3 && p5) { marshal = true; marshalBranches.add(d.branch()); }
            }
        }
        boolean[] achieved = {full, fullBranches.size() == 4, collection.size() == 288, plus3, plus5, marshal, marshalBranches.size() == 4};
        int index = 0;
        for (String key : ACHIEVEMENTS.keySet()) if (achieved[index++] && record && get(playerId, "achievement/" + key) == 0) put(playerId, "achievement/" + key, 1);
        List<Map<String, Object>> awards = new ArrayList<>();
        ACHIEVEMENTS.forEach((key, name) -> awards.add(Map.of("id", key, "name", name, "unlocked", get(playerId, "achievement/" + key) > 0)));
        List<Map<String, Object>> daily = new ArrayList<>();
        for (String task : List.of("train", "wild", "gather", "develop")) {
            String key = "daily/" + today() + "/" + task;
            daily.add(Map.of("id", task, "name", TASKS.get(task), "progress", get(playerId, key), "required", task.equals("train") ? 100 : 1, "claimed", get(playerId, key + "/claimed") > 0));
        }
        Map<String, List<String>> loadouts = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) loadouts.put(String.valueOf(i), loadout(playerId, i));
        return Map.of("loadouts", loadouts, "parts", items.findByPlayerIdAndItemKey(playerId, PARTS).map(PlayerItem::getCount).orElse(0),
                "collection", collection, "achievements", awards, "daily", daily, "dailyClaims", get(playerId, "daily/" + today() + "/claims"),
                "weeklyProgress", get(playerId, "week/" + week()), "weeklyClaimed", get(playerId, "week/" + week() + "/claimed") > 0);
    }
}
