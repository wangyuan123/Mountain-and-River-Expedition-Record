package com.wargame.service;

import com.wargame.model.constants.GameData;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class WoundedService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.compliance.AntiAddictionService protection;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;
    public static final long RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    private final WoundedUnitRepository wounded;
    private final ArmyUnitRepository army;
    private final ResourcesRepository resources;
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final TechnologyRepository technologies;

    public WoundedService(WoundedUnitRepository wounded, ArmyUnitRepository army,
                          ResourcesRepository resources, PlayerRepository players,
                          PlayerCityRepository cities, TechnologyRepository technologies) {
        this.wounded = wounded;
        this.army = army;
        this.resources = resources;
        this.players = players;
        this.cities = cities;
        this.technologies = technologies;
    }

    public int medicalLevel(Long playerId) {
        return technologies.findByPlayerIdAndType(playerId, "log_medical").stream().findFirst()
                .map(t -> Math.max(0, Math.min(10, t.getLevel() == null ? 0 : t.getLevel()))).orElse(0);
    }

    public int recoveryPercent(Long playerId, Officer commander) {
        int medic = 0;
        if (commander != null && playerId.equals(commander.getPlayerId())) {
            for (Map<String, Object> skill : JsonUtil.parseList(commander.getSkills())) {
                if ("medic".equals(skill.get("id")) && skill.get("lv") instanceof Number level)
                    medic = Math.max(medic, Math.max(0, Math.min(5, level.intValue())));
            }
        }
        return medicalLevel(playerId) * 5 + medic * 3;
    }

    @Transactional
    public Map<String, Integer> recordOutboundLosses(Long playerId, March march,
                                                     Map<String, Integer> initial, Map<String, Integer> survivors,
                                                     Officer commander, long now) {
        // 地图中的玩家位置可以离开城市；这种出征仍归主城救治，不能创建虚构城市。
        PlayerCity origin = cities.findByOwnerId(playerId).stream()
                .filter(c -> Objects.equals(c.getX(), march.getFromX()) && Objects.equals(c.getY(), march.getFromY()))
                .findFirst().orElse(null);
        return recordLosses(playerId, origin == null ? null : origin.getX(), origin == null ? null : origin.getY(),
                initial, survivors, commander, now);
    }

    /** 只接收实际参战损失；城防不属于可治疗部队。回收率在战斗结束时锁定。 */
    @Transactional
    public Map<String, Integer> recordLosses(Long playerId, Integer cityX, Integer cityY,
                                            Map<String, Integer> initial, Map<String, Integer> survivors,
                                            Officer commander, long now) {
        Map<String, Integer> recovered = new LinkedHashMap<>();
        if (initial == null || initial.isEmpty()) return recovered;
        int percent = recoveryPercent(playerId, commander);
        if (percent == 0) return recovered;
        Player player = players.findById(playerId).orElseThrow();
        int x = cityX != null ? cityX : (cityScope.economy(playerId).getCityPosX() == null ? 0 : cityScope.economy(playerId).getCityPosX());
        int y = cityY != null ? cityY : (cityScope.economy(playerId).getCityPosY() == null ? 0 : cityScope.economy(playerId).getCityPosY());
        PlayerCity city = cities.findByOwnerId(playerId).stream()
                .filter(c -> Objects.equals(c.getX(), x) && Objects.equals(c.getY(), y)).findFirst().orElse(null);
        String name = city != null ? city.getName() : cityScope.economy(playerId).getCityName();
        for (var entry : initial.entrySet()) {
            if (!GameData.UNITS.containsKey(entry.getKey())) continue;
            int start = Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
            int alive = survivors == null ? 0 : Math.max(0, survivors.getOrDefault(entry.getKey(), 0));
            int count = (int) (Math.max(0L, (long) start - alive) * percent / 100);
            if (count == 0) continue;
            WoundedUnit batch = new WoundedUnit();
            batch.setPlayerId(playerId);
            batch.setCitySlot(city != null ? Objects.requireNonNullElse(city.getCitySlot(), 0) : cityScope.slot(playerId));
            batch.setCityId(city == null ? null : city.getId());
            batch.setCityX(x);
            batch.setCityY(y);
            batch.setCityName(name == null || name.isBlank() ? "城市" : name);
            batch.setType(entry.getKey());
            batch.setCount(count);
            batch.setCreatedAt(now);
            batch.setExpiresAt(protection.treatmentDeadline(playerId, now + RETENTION_MS));
            batch.setRecoveryPercent(percent);
            wounded.save(batch);
            recovered.put(entry.getKey(), count);
        }
        return recovered;
    }

    /** 黄金为制造资源总价的 10%（向上取整），钻石按每 100 黄金折算一颗。 */
    public long goldCost(String unit, int count) {
        var definition = GameData.UNITS.get(unit);
        if (definition == null) throw new IllegalArgumentException("未知兵种");
        long productionCost = definition.cost().values().stream().mapToLong(Integer::longValue).sum();
        return Math.max(1L, (productionCost + 9) / 10) * count;
    }

    public long diamondCost(String unit, int count) {
        return (goldCost(unit, count) + 99) / 100;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCamp(Long playerId) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> batches = new ArrayList<>();
        long total = 0;
        for (WoundedUnit batch : wounded.findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(playerId, cityScope.slot(playerId), now)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", batch.getId());
            item.put("cityId", batch.getCityId());
            item.put("cityX", batch.getCityX());
            item.put("cityY", batch.getCityY());
            item.put("cityName", batch.getCityName());
            item.put("unit", batch.getType());
            item.put("count", batch.getCount());
            item.put("createdAt", batch.getCreatedAt());
            item.put("expiresAt", batch.getExpiresAt());
            item.put("recoveryPercent", batch.getRecoveryPercent());
            item.put("goldPerUnit", goldCost(batch.getType(), 1));
            item.put("goldCost", goldCost(batch.getType(), batch.getCount()));
            item.put("diamondCost", diamondCost(batch.getType(), batch.getCount()));
            batches.add(item);
            total += batch.getCount();
        }
        return Map.of("batches", batches, "total", total, "medicalLevel", medicalLevel(playerId),
                "serverTime", now);
    }

    @Transactional
    public Map<String, Object> heal(Long playerId, Long batchId, Integer count, String currency) {
        if (batchId == null || count == null || count <= 0) throw new IllegalArgumentException("请输入有效的治疗数量");
        if (!"gold".equals(currency) && !"diamond".equals(currency)) throw new IllegalArgumentException("请选择黄金或钻石");
        // 同一玩家治疗串行化，跨批次治疗也不能重复消费或重复生成新兵种记录。
        Resources balance = resources.findCityForTreatment(playerId, "diamond".equals(currency) ? 0 : cityScope.slot(playerId)).orElseThrow(() -> new IllegalArgumentException("资源不存在"));
        WoundedUnit batch = wounded.findForTreatment(playerId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("伤兵不存在或已治疗"));
        if (batch.getCitySlot() != cityScope.slot(playerId)) throw new IllegalArgumentException("请切换到伤兵所属城市后治疗");
        if (batch.getExpiresAt() <= System.currentTimeMillis()) throw new IllegalArgumentException("该批伤兵已超过救治期限");
        if (count > batch.getCount()) throw new IllegalArgumentException("治疗数量超过剩余伤兵");
        long cost = "gold".equals(currency) ? goldCost(batch.getType(), count) : diamondCost(batch.getType(), count);
        int available = "gold".equals(currency) ? Objects.requireNonNullElse(balance.getGold(), 0) : Objects.requireNonNullElse(balance.getDiamond(), 0);
        if (available < cost) throw new IllegalArgumentException("gold".equals(currency) ? "黄金不足，可减少数量或选择钻石治疗" : "钻石不足，可减少数量或选择黄金治疗");
        if ("gold".equals(currency)) balance.setGold((int) (available - cost));
        else balance.setDiamond((int) (available - cost));
        List<ArmyUnit> units = army.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), batch.getType());
        ArmyUnit unit = units.isEmpty() ? new ArmyUnit(null, playerId, batch.getType(), 0) : units.get(0);
        unit.setCitySlot(batch.getCitySlot());
        unit.setCount(Math.addExact(Objects.requireNonNullElse(unit.getCount(), 0), count));
        army.save(unit);
        resources.save(balance);
        batch.setCount(batch.getCount() - count);
        if (batch.getCount() == 0) wounded.delete(batch);
        else wounded.save(batch);
        return new LinkedHashMap<>(Map.of("success", true, "message", "治疗完成，" + GameData.UNITS.get(batch.getType()).name() + " ×" + count + " 已归队", "healed", count, "cost", cost));
    }

    @Transactional
    public void expireWounded() {
        wounded.deleteExpired(System.currentTimeMillis());
    }
}
