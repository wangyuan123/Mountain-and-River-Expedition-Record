package com.wargame.service;

import com.wargame.model.constants.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 账号统一配装与城市制造；所有库存变动和队列完成均在玩家锁内提交。 */
@Service
@RequiredArgsConstructor
public class UnitEquipmentService {
    private final UnitLoadoutRepository loadouts;
    private final UnitEquipmentOrderRepository orders;
    private final PlayerItemRepository items;
    private final ResourcesRepository resources;
    private final BuildingRepository buildings;
    private final TechnologyRepository technologies;
    private final AccountService accounts;
    private final CityScope scope;

    public Map<String, Map<String, String>> loadouts(Long player) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (UnitLoadout row : loadouts.findByPlayerId(player)) {
            Map<String, String> slots = new LinkedHashMap<>();
            JsonUtil.parseObjMap(row.getSlots()).forEach((slot, key) -> {
                UnitEquipmentDef d = UnitEquipmentDef.ITEMS.get(String.valueOf(key));
                if (d != null && d.unit().equals(row.getUnitType()) && d.slot().equals(slot)) slots.put(slot, d.key());
            });
            result.put(row.getUnitType(), slots);
        }
        return result;
    }

    public Map<String, Integer> snapshot(Long player) {
        return UnitEquipmentEffects.snapshot(loadouts(player));
    }

    /** 路线预览与派遣冻结负重科技，保持运兵容量和资源负重一致。 */
    public Map<String, Integer> actionSnapshot(Long player) {
        Map<String, Integer> tech = new LinkedHashMap<>(snapshot(player));
        tech.put("log_load", technologies.findByPlayerIdAndType(player, "log_load").stream()
                .mapToInt(t -> Objects.requireNonNullElse(t.getLevel(), 0)).max().orElse(0));
        return TechnologyRules.actionSnapshot(tech);
    }

    /** 一次保存三槽；不读取军队数量，换装旧物返库，非法请求不产生部分扣款。 */
    @Transactional
    public void save(Long player, String unit, Map<String, String> requested, Long version) {
        accounts.lockPlayer(player);
        if (!UnitEquipmentDef.ITEMS.values().stream().anyMatch(d -> d.unit().equals(unit))) throw new IllegalArgumentException("该兵种不支持配装");
        if (requested == null || requested.keySet().stream().anyMatch(k -> !UnitEquipmentDef.SLOTS.contains(k))) throw new IllegalArgumentException("装备槽位无效");
        UnitLoadout row = loadouts.findByPlayerIdAndUnitType(player, unit).orElse(null);
        // 对外版本零表示从未配装；持久化版本加一，避免首次创建后仍接受相同的零版本。
        if (version != null && version != (row == null ? 0 : row.getVersion() + 1)) throw new IllegalArgumentException("配装已在其他页面更新，请刷新后重试");
        Map<String, String> old = loadouts(player).getOrDefault(unit, Map.of()), next = new LinkedHashMap<>();
        for (String slot : UnitEquipmentDef.SLOTS) {
            String key = requested.get(slot);
            if (key == null || key.isBlank()) continue;
            UnitEquipmentDef d = UnitEquipmentDef.ITEMS.get(key);
            if (d == null || !unit.equals(d.unit()) || !slot.equals(d.slot())) throw new IllegalArgumentException("装备不适用于该兵种或槽位");
            next.put(slot, key);
        }
        if (new HashSet<>(next.values()).size() != next.size()) throw new IllegalArgumentException("同一件装备不能重复配置");
        for (var e : next.entrySet()) {
            if (Objects.equals(old.get(e.getKey()), e.getValue())) continue;
            PlayerItem item = items.findByPlayerIdAndItemKey(player, e.getValue()).orElseThrow(() -> new IllegalArgumentException("装备库存不足"));
            if (item.getCount() < 1) throw new IllegalArgumentException("装备库存不足");
            item.setCount(item.getCount() - 1); item.setUpdatedAt(System.currentTimeMillis()); items.save(item);
        }
        old.forEach((slot, key) -> { if (!Objects.equals(next.get(slot), key)) grant(player, key); });
        if (row == null) { row = new UnitLoadout(); row.setPlayerId(player); row.setUnitType(unit); }
        row.setSlots(JsonUtil.toJson(next)); loadouts.saveAndFlush(row);
    }

    /** 固定配方单件制造，城市只有一条独立通道，装备工程只解锁配方。 */
    @Transactional
    public void manufacture(Long player, String key) {
        accounts.lockPlayer(player);
        long now = System.currentTimeMillis();
        complete(player, now);
        UnitEquipmentDef d = UnitEquipmentDef.ITEMS.get(key);
        if (d == null) throw new IllegalArgumentException("装备配方不存在");
        int tech = technologies.findByPlayerIdAndType(player, "unit_refit").stream().mapToInt(t -> Objects.requireNonNullElse(t.getLevel(), 0)).max().orElse(0);
        int factory = facilityLevel(player, d.unit());
        if (tech < d.level()) throw new IllegalArgumentException("需武器装备 Lv." + d.level());
        if (factory < d.level() * 2) throw new IllegalArgumentException("本城对应生产设施需 Lv." + d.level() * 2);
        List<UnitEquipmentOrder> lane = orders.findByPlayerIdAndCitySlotOrderByStartAtAscIdAsc(player, scope.slot(player));
        if (lane.size() >= 100) throw new IllegalArgumentException("装备制造队列已满");
        Resources res = resources.findByPlayerIdAndCitySlot(player, scope.slot(player)).orElseThrow();
        if (Objects.requireNonNullElse(res.getSteel(), 0) < d.cost().get("steel") || Objects.requireNonNullElse(res.getOil(), 0) < d.cost().get("oil") || Objects.requireNonNullElse(res.getRare(), 0) < d.cost().get("rare")) throw new IllegalArgumentException("本城制造资源不足");
        res.setSteel(res.getSteel() - d.cost().get("steel")); res.setOil(res.getOil() - d.cost().get("oil")); res.setRare(res.getRare() - d.cost().get("rare")); resources.save(res);
        long start = lane.isEmpty() ? now : Math.max(now, lane.get(lane.size() - 1).getEndAt());
        UnitEquipmentOrder order = new UnitEquipmentOrder(); order.setPlayerId(player); order.setCitySlot(scope.slot(player));
        order.setItemKey(key); order.setCost(JsonUtil.toJson(d.cost())); order.setStartAt(start);
        order.setEndAt(start + (long) Math.ceil(d.seconds() * 1000 / (1 + .05 * (factory - 1)))); orders.save(order);
    }

    @Transactional
    public void cancel(Long player, Long id) {
        accounts.lockPlayer(player);
        long now = System.currentTimeMillis(); complete(player, now);
        UnitEquipmentOrder order = orders.findById(id).filter(o -> player.equals(o.getPlayerId()) && o.getCitySlot() == scope.slot(player)).orElseThrow(() -> new IllegalArgumentException("制造订单不存在"));
        double refund = order.getStartAt() > now ? 1 : .5;
        Map<String, Integer> cost = JsonUtil.parseIntMap(order.getCost());
        Resources res = resources.findByPlayerIdAndCitySlot(player, order.getCitySlot()).orElseThrow();
        res.setSteel(res.getSteel() + (int) Math.floor(cost.getOrDefault("steel", 0) * refund));
        res.setOil(res.getOil() + (int) Math.floor(cost.getOrDefault("oil", 0) * refund));
        res.setRare(res.getRare() + (int) Math.floor(cost.getOrDefault("rare", 0) * refund)); resources.save(res);
        orders.delete(order); orders.flush();
        long tail = now;
        for (var o : orders.findByPlayerIdAndCitySlotOrderByStartAtAscIdAsc(player, scope.slot(player))) {
            long duration = o.getEndAt() - o.getStartAt();
            if (o.getStartAt() > now) { o.setStartAt(tail); o.setEndAt(tail + duration); orders.save(o); }
            tail = o.getEndAt();
        }
    }

    /** 与城市 tick 共用玩家锁；删除已完成订单与发货同事务，防止重复领取。 */
    @Transactional
    public boolean complete(Long player, long now) {
        accounts.lockPlayer(player);
        boolean completed = false;
        for (var order : orders.findByPlayerIdAndCitySlotOrderByStartAtAscIdAsc(player, scope.slot(player))) {
            if (order.getEndAt() > now) break;
            grant(player, order.getItemKey()); orders.delete(order);
            completed = true;
        }
        orders.flush();
        return completed;
    }

    public Map<String, Integer> inventory(Long player) {
        Map<String, Integer> result = new LinkedHashMap<>();
        items.findByPlayerId(player).forEach(item -> result.put(item.getItemKey(), item.getCount()));
        return result;
    }

    private void grant(Long player, String key) {
        PlayerItem item = items.findByPlayerIdAndItemKey(player, key).orElseGet(() -> {
            PlayerItem value = new PlayerItem(); value.setPlayerId(player); value.setItemKey(key); value.setCount(0); return value;
        });
        item.setCount(item.getCount() + 1); item.setUpdatedAt(System.currentTimeMillis()); items.save(item);
    }

    private int facilityLevel(Long player, String unit) {
        return buildings.findByPlayerIdAndCitySlotAndType(player, scope.slot(player), UnitDef.UNITS.get(unit).build()).stream().mapToInt(b -> Objects.requireNonNullElse(b.getLevel(), 0)).max().orElse(0);
    }

    /** 旧版客户端存档不具有制造凭证，仅清理城市订单，不从 JSON 生成物品。 */
    @Transactional
    public void clearOrders(Long player) { orders.deleteByPlayerId(player); }

    public Map<String, Object> state(Long player) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loadouts", loadouts(player));
        Map<String, Long> versions = new LinkedHashMap<>(); loadouts.findByPlayerId(player).forEach(r -> versions.put(r.getUnitType(), r.getVersion() + 1));
        result.put("versions", versions); result.put("snapshot", snapshot(player)); result.put("catalog", UnitEquipmentDef.ITEMS);
        Map<String, Integer> facilities = new LinkedHashMap<>(); UnitEquipmentDef.ITEMS.values().forEach(d -> facilities.computeIfAbsent(d.unit(), u -> facilityLevel(player, u)));
        result.put("facilities", facilities);
        result.put("queue", orders.findByPlayerIdAndCitySlotOrderByStartAtAscIdAsc(player, scope.slot(player)));
        return result;
    }
}
