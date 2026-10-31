package com.wargame.service;

import com.wargame.util.GameClock;
import com.wargame.model.constants.GameData;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class WoundedService {
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TickService resourceEconomy;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.compliance.AntiAddictionService protection;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private VipService vipService;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private GarrisonFoodService garrisonFood;
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

    /** 出征和援军伤兵归行军的城市槽；历史坐标和玩家后来切换的城市不能改变归属。 */
    @Transactional
    public Map<String, Integer> recordOutboundLosses(Long playerId, March march,
                                                     Map<String, Integer> initial, Map<String, Integer> survivors,
                                                     Officer commander, long now) {
        try (var ignored = cityScope.enter(playerId, Objects.requireNonNullElse(march.getCitySlot(), 0))) {
            return recordLosses(playerId, null, null, initial, survivors, commander, now);
        }
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
        players.findById(playerId).orElseThrow();
        CityEconomy economy = cityScope.economy(playerId);
        int x = cityX != null ? cityX : Objects.requireNonNullElse(economy.getCityPosX(), 0);
        int y = cityY != null ? cityY : Objects.requireNonNullElse(economy.getCityPosY(), 0);
        PlayerCity city = cityX == null && cityY == null ? cityScope.selected(playerId).orElse(null) : cities.findByOwnerId(playerId).stream()
                .filter(c -> Objects.equals(c.getX(), x) && Objects.equals(c.getY(), y)).findFirst().orElse(null);
        String name = city != null ? city.getName() : economy.getCityName();
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
            // 合规开放日历使用真实日期，输入输出需换算回活动的游戏时间轴。
            long wallOffset = System.currentTimeMillis() - GameClock.now();
            batch.setExpiresAt(protection.treatmentDeadline(playerId, now + RETENTION_MS + wallOffset) - wallOffset);
            batch.setRecoveryPercent(percent);
            wounded.save(batch);
            recovered.put(entry.getKey(), count);
        }
        return recovered;
    }

    /** 黄金为制造资源总价的 10%（每单位向上取整）。 */
    public long goldCost(String unit, int count) {
        var definition = GameData.UNITS.get(unit);
        if (definition == null) throw new IllegalArgumentException("未知兵种");
        long productionCost = definition.cost().values().stream().mapToLong(Integer::longValue).sum();
        return Math.max(1L, (productionCost + 9) / 10) * count;
    }

    /** VIP 特许专属折后黄金费用。 */
    public long goldCost(Long playerId, String unit, int count) {
        long original = goldCost(unit, count);
        if (playerId == null || vipService == null) return original;
        Player p = players.findById(playerId).orElse(null);
        double discount = vipService.getWoundedGoldDiscount(p);
        if (discount > 0) {
            long percent = Math.round(discount * 100);
            return Math.max(1L, (original * (100 - percent) + 99) / 100);
        }
        return original;
    }

    /** 钻石按本次治疗的黄金总费用每 1000 黄金折算一颗，不足一颗向上取整。 */
    public long diamondCost(String unit, int count) {
        return (goldCost(unit, count) + 999) / 1000;
    }

    /** 数量报价与最大可支付数量都复用实际治疗费用，跨城和过期对象不参与预览。 */
    public Map<String, Object> preview(Long playerId, Long batchId, int count) {
        var batch = wounded.findById(batchId).filter(b -> playerId.equals(b.getPlayerId())
                && b.getCitySlot() == cityScope.slot(playerId)).orElseThrow(() -> new IllegalArgumentException("伤兵不存在"));
        long now = GameClock.now();
        var city = cityScope.selected(playerId).orElse(null);
        long validAt = city != null && city.isSurrenderFrozen(now) ? city.getSurrenderFrozenAt() : now;
        // 冻结城市暂停救治倒计时，与列表和实际治疗使用同一有效时点。
        if (count < 0 || count > batch.getCount() || batch.getExpiresAt() <= validAt) throw new IllegalArgumentException("伤兵数量或期限无效");
        long gold = resources.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).map(Resources::getGold).orElse(0L);
        long diamond = Objects.requireNonNullElse(cityScope.wallet(playerId).getDiamond(), 0);
        return Map.of("count", count, "gold", count == 0 ? 0 : goldCost(playerId, batch.getType(), count),
                "originalGold", goldCost(batch.getType(), count), "diamond", diamondCost(batch.getType(), count),
                "maxGold", maximumAffordable(playerId, batch, gold, false), "maxDiamond", maximumAffordable(playerId, batch, diamond, true));
    }

    private int maximumAffordable(Long playerId, WoundedUnit batch, long balance, boolean diamond) {
        int low = 0, high = batch.getCount();
        while (low < high) {
            int mid = low + (int) (((long) high - low + 1) / 2);
            long price = diamond ? diamondCost(batch.getType(), mid) : goldCost(playerId, batch.getType(), mid);
            if (price <= balance) low = mid; else high = mid - 1;
        }
        return low;
    }

    /** 返回当前请求城市的批次与余额；坐标仅为展示快照，不能用于划分城市。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getCamp(Long playerId) {
        long now = GameClock.now();
        int citySlot = cityScope.slot(playerId);
        Player player = players.findById(playerId).orElse(null);
        double vipDiscount = vipService != null ? vipService.getWoundedGoldDiscount(player) : 0.0;
        int vipDiscountPercent = (int) Math.round(vipDiscount * 100);
        PlayerCity selected = cityScope.selected(playerId).orElse(null);
        boolean frozen = selected != null && selected.isSurrenderFrozen(now);
        long validAt = frozen ? selected.getSurrenderFrozenAt() : now;

        List<Map<String, Object>> batches = new ArrayList<>();
        long total = 0;
        for (WoundedUnit batch : wounded.findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(playerId, citySlot, validAt)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", batch.getId());
            item.put("citySlot", batch.getCitySlot());
            item.put("cityId", batch.getCityId());
            item.put("cityX", batch.getCityX());
            item.put("cityY", batch.getCityY());
            item.put("cityName", batch.getCityName());
            item.put("unit", batch.getType());
            item.put("count", batch.getCount());
            item.put("createdAt", batch.getCreatedAt());
            item.put("expiresAt", batch.getExpiresAt() + (frozen ? now - validAt : 0));
            item.put("frozen", frozen);
            item.put("recoveryPercent", batch.getRecoveryPercent());

            long origGoldPerUnit = goldCost(batch.getType(), 1);
            long origGoldCost = goldCost(batch.getType(), batch.getCount());
            long finalGoldPerUnit = goldCost(playerId, batch.getType(), 1);
            long finalGoldCost = goldCost(playerId, batch.getType(), batch.getCount());

            item.put("originalGoldPerUnit", origGoldPerUnit);
            item.put("originalGoldCost", origGoldCost);
            item.put("goldPerUnit", finalGoldPerUnit);
            item.put("goldCost", finalGoldCost);
            item.put("vipDiscountPercent", vipDiscountPercent);
            item.put("diamondCost", diamondCost(batch.getType(), batch.getCount()));
            item.put("preview", preview(playerId, batch.getId(), batch.getCount()));
            batches.add(item);
            total += batch.getCount();
        }
        Map<String, Object> camp = new LinkedHashMap<>();
        camp.put("batches", batches);
        camp.put("total", total);
        camp.put("medicalLevel", medicalLevel(playerId));
        camp.put("medicalPercent", recoveryPercent(playerId, null));
        camp.put("vipDiscountPercent", vipDiscountPercent);
        camp.put("maximumRecoveryPercent", 65);
        camp.put("serverTime", now);
        camp.put("citySlot", citySlot);
        CityEconomy economy = cityScope.economy(playerId);
        camp.put("cityId", cityScope.selected(playerId).map(PlayerCity::getId).orElse(null));
        camp.put("cityName", economy.getCityName());
        camp.put("cityX", economy.getCityPosX());
        camp.put("cityY", economy.getCityPosY());
        // 黄金属本城，钻石沿用账号共享钱包；按钮和实际扣款使用相同的资产范围。
        Resources wallet = cityScope.wallet(playerId);
        camp.put("resources", Map.of(
                "gold", resources.findByPlayerIdAndCitySlot(playerId, citySlot).map(Resources::getGold).orElse(0L),
                "diamond", Objects.requireNonNullElse(wallet.getDiamond(), 0)));
        return camp;
    }

    /** 先在账号锁内结清归队前的粮账，再消费治疗货币并增加现役兵力。 */
    @Transactional
    public Map<String, Object> heal(Long playerId, Long batchId, Integer count, String currency) {
        if (batchId == null || count == null || count <= 0) throw new IllegalArgumentException("请输入有效的治疗数量");
        if (!"gold".equals(currency) && !"diamond".equals(currency)) throw new IllegalArgumentException("请选择黄金或钻石");
        players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        resourceEconomy.beforeResourceChange(playerId, cityScope.slot(playerId));
        long now = GameClock.now();
        // 未启用粮账的城市从旧经济时刻接管，防止下一次 tick 把新兵计入治疗前的区间。
        cityScope.selected(playerId).ifPresent(city -> garrisonFood.activate(city, now));
        // 同一玩家治疗串行化，跨批次治疗也不能重复消费或重复生成新兵种记录。
        Resources balance = resources.findCityForTreatment(playerId, "diamond".equals(currency) ? 0 : cityScope.slot(playerId)).orElseThrow(() -> new IllegalArgumentException("资源不存在"));
        WoundedUnit batch = wounded.findForTreatment(playerId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("伤兵不存在或已治疗"));
        if (batch.getCitySlot() != cityScope.slot(playerId)) throw new IllegalArgumentException("请切换到伤兵所属城市后治疗");
        if (batch.getExpiresAt() <= now) throw new IllegalArgumentException("该批伤兵已超过救治期限");
        if (count > batch.getCount()) throw new IllegalArgumentException("治疗数量超过剩余伤兵");
        long cost = "gold".equals(currency) ? goldCost(playerId, batch.getType(), count) : diamondCost(batch.getType(), count);
        long available = "gold".equals(currency) ? Objects.requireNonNullElse(balance.getGold(), 0L) : Objects.requireNonNullElse(balance.getDiamond(), 0).longValue();
        if (available < cost) throw new IllegalArgumentException("gold".equals(currency) ? "黄金不足，可减少数量或选择钻石治疗" : "钻石不足，可减少数量或选择黄金治疗");
        if ("gold".equals(currency)) balance.setGold(available - cost);
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
        wounded.deleteExpired(GameClock.now());
    }
}
