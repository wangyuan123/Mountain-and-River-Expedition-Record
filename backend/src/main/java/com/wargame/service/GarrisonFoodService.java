package com.wargame.service;

import com.wargame.model.constants.GameData;
import com.wargame.model.dto.JointDefenseState;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 驻防城市粮食事件账：库存部队与外来驻军同城计量，小数保留，不向派兵城重复收费。 */
@Service
@RequiredArgsConstructor
public class GarrisonFoodService {
    private final PlayerCityRepository cities;
    private final MarchRepository marches;
    private final BattleSessionRepository battles;
    private final ResourcesRepository stocks;
    private final OfficerRepository officers;
    private final CityScope scope;
    private final MarchTargetService targets;
    @org.springframework.beans.factory.annotation.Autowired private WebSocketPushService pushes;
    @org.springframework.beans.factory.annotation.Autowired private GarrisonFoodTimeline timeline;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TickService economy;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private MarchService movement;

    /** 新抵达事件启用账本前补齐本城旧时段，旧驻军上线则从首次结算时刻起算。 */
    public void activate(PlayerCity city, long now) {
        if (city.getGarrisonFoodAt() == null) {
            try (var ignored = scope.enter(city)) { city.setGarrisonFoodAt(Math.min(now, Objects.requireNonNullElse(scope.economy(city.getOwnerId()).getLastTick(), now))); }
        }
        settle(city, now);
    }

    public List<March> stationed(PlayerCity city) {
        return marches.findByTargetKindAndTargetIdAndStationedTrueOrderByIdAsc("player", city.getId().toString());
    }
    /** 账号共享科技变化会影响其全部接收城市，须先逐城结清旧配置区间。 */
    public void beforeGlobalChange(Long owner, long now) {
        cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(owner).forEach(c -> settleInterval(c, now));
    }
    /** 到期队列已由外层查询取得，不能在其变更回调中再次完成同一订单。 */
    public void beforeScheduledChange(Long owner, long now) { scope.selected(owner).ifPresent(c -> settleInterval(c, now)); }
    public boolean managed(PlayerCity city) { return city != null && (city.getGarrisonFoodAt() != null || !stationed(city).isEmpty()); }
    public static double baseUse(Map<String, Integer> army) {
        return army.entrySet().stream().mapToDouble(e -> GameData.UNITS.containsKey(com.wargame.model.constants.UnitEquipmentEffects.base(e.getKey()))
                ? (double) GameData.UNITS.get(com.wargame.model.constants.UnitEquipmentEffects.base(e.getKey())).food() * e.getValue() : 0).sum();
    }
    /** 来源粮耗采用本场已经持久化的存活编队，而非尚未终局扣除的原始驻军数量。 */
    public Map<String, Integer> living(March march, List<BattleSession> sessions) {
        for (BattleSession s : sessions) {
            JointDefenseState state = JointDefenseState.read(s.getJointDefense());
            if (state == null || !state.getFormations().containsKey(march.getId().toString())) continue;
            Map<String, Integer> alive = JsonUtil.parseIntMap(s.getDefenderArmy());
            Map<String, Integer> result = new LinkedHashMap<>();
            state.getFormations().get(march.getId().toString()).getInitial().keySet().forEach(id -> result.put(id, alive.getOrDefault(id, 0)));
            return result;
        }
        return JsonUtil.parseIntMap(march.getArmy());
    }
    public double reduction(PlayerCity city) {
        try (var ignored = scope.enter(city)) {
            int tech = targets.getTechMap(city.getOwnerId()).getOrDefault("log_food", 0);
            Officer mayor = officers.findByPlayerIdAndCitySlot(city.getOwnerId(), city.getCitySlot()).stream()
                    .filter(o -> "mayor".equals(o.getRole())).findFirst().orElse(null);
            int ration = targets.getCommanderSkills(mayor).getOrDefault("ration", 0);
            return Math.max(.1, Math.max(.5, 1 - .05 * Math.max(0, Math.min(10, tech))) * (1 - .16 * Math.max(0, Math.min(5, ration))));
        }
    }
    public double production(PlayerCity city) {
        try (var ignored = scope.enter(city)) {
            String status = economy.getCityStatus(city.getOwnerId());
            return economy.produceOf(city.getOwnerId(), "farm") * ("war".equals(status) ? .7 : "shield".equals(status) ? 1.1 : 1);
        }
    }
    public double ownUse(PlayerCity city) {
        try (var ignored = scope.enter(city)) {
            // 同城多兵种和百万级兵力不先经旧概览的 int 汇总，避免粮耗溢出为负数。
            double use = baseUse(targets.getTargetArmy(city));
            for (BattleSession s : battles.findActivePlayerCityBattles(city.getId().toString())) {
                JointDefenseState state = JointDefenseState.read(s.getJointDefense());
                if (state == null) continue;
                var home = state.getFormations().get("0");
                Map<String, Integer> alive = JsonUtil.parseIntMap(s.getDefenderArmy());
                Map<String, Integer> dead = new LinkedHashMap<>();
                home.getInitial().forEach((id, n) -> dead.put(id, Math.max(0, n - alive.getOrDefault(id, 0))));
                use -= baseUse(dead);
            }
            return Math.max(0, use);
        }
    }
    /** 返回本城承担的外来驻军每小时粮耗，供面板与缺粮政策共同使用。 */
    public double supportUse(PlayerCity city) {
        List<BattleSession> sessions = battles.findActivePlayerCityBattles(city.getId().toString());
        return stationed(city).stream().mapToDouble(m -> baseUse(living(m, sessions))).sum() * reduction(city);
    }

    /** 当前城市粮耗面板与实际粮账使用相同存活兵力及城主减耗配置，外来驻军单列。 */
    public Map<String, Double> supplyView(Long owner) {
        PlayerCity city = scope.selected(owner).orElse(null);
        if (city == null) return null;
        double own = ownUse(city) * reduction(city), support = supportUse(city);
        return Map.of("ownPerHour", own, "supportPerHour", support, "totalPerHour", own + support,
                "productionPerHour", production(city));
    }
    /** 只在城主锁内调用；事件时刻单调递增，重复回合或重复抵达不会再次扣粮。 */
    @Transactional
    public void settle(PlayerCity city, long now) {
        if (city == null || city.getOwnerId() == null) return;
        Long eventTime = timeline.eventTime();
        if (eventTime != null) now = Math.min(now, eventTime);
        else timeline.advance(city.getOwnerId(), now, null);
        settleInterval(city, now);
    }

    /** 回合已由调用者锁定；先处理之前的经济事件，不能在粮账内再次执行当前回合。 */
    public void beforeBattle(PlayerCity city, long now, Long sessionId) {
        if (timeline.eventTime() == null) timeline.advance(city.getOwnerId(), now, sessionId);
        settleInterval(city, now);
    }

    private void settleInterval(PlayerCity city, long now) {
        if (city == null || city.getOwnerId() == null || city.getCitySlot() == null || !managed(city)) return;
        List<March> supports = stationed(city);
        if (city.getGarrisonFoodAt() == null) {
            city.setGarrisonFoodAt(now); supports.forEach(m -> m.setGarrisonFoodAt(now)); cities.save(city); return;
        }
        long from = city.getGarrisonFoodAt();
        if (now <= from) return;
        List<BattleSession> sessions = battles.findActivePlayerCityBattles(city.getId().toString());
        // 未推进的旧回合必须先按截止时刻结算，不能用回合初兵力跨过未来伤亡预扣。
        long through = sessions.stream().filter(s -> s.getJointDefense() != null).map(BattleSession::getRoundDeadlineAt)
                .filter(Objects::nonNull).mapToLong(Long::longValue).filter(t -> t >= from).min().orElse(now);
        through = Math.min(now, through);
        Resources stock = stocks.findByPlayerIdAndCitySlot(city.getOwnerId(), city.getCitySlot()).orElse(null);
        if (stock == null) return;
        int remainingEvents = supports.size() + 2;
        while (city.getGarrisonFoodAt() < through && remainingEvents-- > 0) {
            long cursor = city.getGarrisonFoodAt();
            List<March> liveSupport = stationed(city);
            double support = liveSupport.stream().mapToDouble(m -> baseUse(living(m, sessions))).sum();
            double net = production(city) - (ownUse(city) + support) * reduction(city);
            // 普通经济转入驻军粮账时一并接管尚未入整的小数，随后由城市粮账唯一维护。
            double exact = exactFood(city, stock);
            stock.setFoodAccrual(0);
            long end = through;
            boolean depleted = net < 0 && exact + net * (through - cursor) / 3600000.0 <= 0;
            // 离线同样在实际耗尽时刻返程，不能把整段离线时长都按已经遣返的兵力收费。
            if (depleted && !liveSupport.isEmpty()) end = Math.min(through, cursor + Math.max(1, (long) Math.ceil(exact / -net * 3600000)));
            double after = exact + net * (end - cursor) / 3600000.0;
            if (after < 0) { city.setGarrisonFoodUnmet(city.getGarrisonFoodUnmet() - after); after = 0; }
            long cap;
            try (var ignored = scope.enter(city)) { cap = economy.capacity(city.getOwnerId()).get("food"); }
            if (net > 0) after = Math.min(Math.max(cap, Objects.requireNonNullElse(stock.getFood(), 0L)), after);
            stock.setFood((long) Math.floor(after)); city.setGarrisonFoodRemainder(after - Math.floor(after)); city.setGarrisonFoodAt(end);
            final long billed = end; liveSupport.forEach(m -> m.setGarrisonFoodAt(billed));
            stocks.save(stock); cities.save(city);
            if (depleted) {
                enforce(city, end);
                if (stationed(city).size() == liveSupport.size() && end < through) {
                    // 锁定中的军队必须打完；仅登记缺粮，不建立负债，也不循环重试同一耗尽事件。
                    city.setGarrisonFoodUnmet(city.getGarrisonFoodUnmet() + Math.max(0, -net * (through - end) / 3600000.0));
                    city.setGarrisonFoodAt(through);
                }
            }
        }
    }
    /** 两种粮账交接期间也保留未入整库存，避免接收驻军时丢失或重复使用小数。 */
    private double exactFood(PlayerCity city, Resources stock) {
        return Objects.requireNonNullElse(stock.getFood(), 0L) + stock.getFoodAccrual() + city.getGarrisonFoodRemainder();
    }

    public boolean supplied(PlayerCity city) {
        Resources stock = stocks.findByPlayerIdAndCitySlot(city.getOwnerId(), city.getCitySlot()).orElse(null);
        return stock != null && (exactFood(city, stock) > 1e-9
                || production(city) >= ownUse(city) * reduction(city) + supportUse(city));
    }
    /** 城主拒收或缺粮只影响新抵达；存量驻军继续保留并参与已锁定战斗。 */
    public boolean mayArrive(PlayerCity city) { return city != null && city.isAcceptGarrison() && supplied(city); }

    /** 零库存时接收校验包含本次新增粮耗；有随军粮食则按交付后的正常城市库存判断。 */
    public boolean mayArrive(PlayerCity city, Map<String, Integer> incoming, Map<String, Integer> cargo) {
        if (city == null || !city.isAcceptGarrison()) return false;
        Resources stock = stocks.findByPlayerIdAndCitySlot(city.getOwnerId(), city.getCitySlot()).orElse(null);
        return stock != null && (exactFood(city, stock)
                + Math.max(0, cargo.getOrDefault("food", 0)) > 1e-9
                || production(city) >= (ownUse(city) + baseUse(incoming)) * reduction(city) + supportUse(city));
    }

    /** 未参战整队按有效粮耗降序、同值最新优先遣返；锁定编队等终局再评估。 */
    @Transactional
    public void enforce(PlayerCity city, long now) {
        if (city == null) return;
        if (supplied(city)) { city.setGarrisonSupplyWarned(false); return; }
        List<BattleSession> sessions = battles.findActivePlayerCityBattles(city.getId().toString());
        List<March> candidates = new ArrayList<>(stationed(city));
        if (!candidates.isEmpty() && !city.isGarrisonSupplyWarned()) {
            city.setGarrisonSupplyWarned(true);
            pushes.pushToPlayer(city.getOwnerId(), "support_update", Map.of("message", "驻防城市粮食不足：拒收新增驻军；参战驻军打完本场后重新评估遣返"));
            candidates.stream().map(March::getPlayerId).distinct().forEach(id -> pushes.pushToPlayer(id, "support_update",
                    Map.of("message", "驻防城市粮食不足，未参战驻军将整队返程，已参战编队完成本场后处理")));
        }
        candidates.sort(Comparator.comparingDouble((March m) -> baseUse(living(m, sessions))).reversed()
                .thenComparing(Comparator.comparingLong((March m) -> Objects.requireNonNullElse(m.getArriveAt(), 0L)).reversed()).thenComparing(March::getId));
        for (March m : candidates) {
            if (locked(m, sessions)) continue;
            movement.returnSupportForSupply(m, now);
            if (supplied(city)) break;
        }
    }
    public boolean locked(March march, List<BattleSession> sessions) {
        return sessions.stream().anyMatch(s -> JsonUtil.parseObjMap(s.getDefenderSupport()).containsKey(march.getId().toString()));
    }
    @Transactional
    public void beforeChange(Long owner) { scope.selected(owner).ifPresent(city -> settle(city, GameClock.now())); }
}
