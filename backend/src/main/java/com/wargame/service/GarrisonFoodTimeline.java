package com.wargame.service;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/** 粮账先重放同一游戏时间轴上的到期事件，避免离线时用旧费率跨过科技、产兵及回合伤亡。 */
@Service
@RequiredArgsConstructor
public class GarrisonFoodTimeline {
    private final PlayerCityRepository cities;
    private final ConstructionRepository construction;
    private final TechResearchQueueRepository research;
    private final ArmyProductionQueueRepository production;
    private final BattleSessionRepository battles;
    private final MarchRepository marches;
    private final CityScope scope;
    @Autowired @Lazy private TickService economy;
    @Autowired private CityStateRepository states;
    @Autowired @Lazy private GarrisonFoodService food;
    @Autowired @Lazy private BuildService builds;
    @Autowired @Lazy private TechService tech;
    @Autowired @Lazy private ArmyService army;
    @Autowired @Lazy private MarchService movement;
    private final ThreadLocal<Long> eventTime = new ThreadLocal<>();
    private final Object completionKey = new Object();
    public Long eventTime() { return eventTime.get(); }
    private record Event(long time, int kind, Long id, PlayerCity city) {}

    /** 调用方已经取得参与者账号锁；当前正在执行的回合不得再次递归推进。 */
    public record Completions(Map<Integer, List<String>> builds, Map<Integer, List<String>> research) {
        public List<String> buildsFor(int slot) { return builds.getOrDefault(slot, List.of()); }
        public List<String> researchFor(int slot) { return research.getOrDefault(slot, List.of()); }
    }

    /** 完工通知保留在当前事务，后续粮账查询不能把已经删除队列的通知吞掉。 */
    @SuppressWarnings("unchecked")
    private Map<Long, Completions> pending() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return new HashMap<>();
        Map<Long, Completions> value = (Map<Long, Completions>) org.springframework.transaction.support.TransactionSynchronizationManager.getResource(completionKey);
        if (value == null) {
            value = new HashMap<>();
            org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(completionKey, value);
            Map<Long, Completions> bound = value;
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void suspend() {
                    org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(completionKey);
                }
                @Override public void resume() {
                    org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(completionKey, bound);
                }
                @Override public void afterCompletion(int status) {
                    org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(completionKey);
                }
            });
        }
        return value;
    }

    /** 每座城市只取走自己的完工通知；共享科技表在各城 Tick 中一并发布。 */
    public Completions drain(Long owner, int slot) {
        Completions saved = pending().get(owner);
        if (saved == null) return new Completions(Map.of(), Map.of());
        List<String> b = saved.builds.remove(slot), r = saved.research.remove(slot);
        return new Completions(Map.of(slot, b == null ? List.of() : b), Map.of(slot, r == null ? List.of() : r));
    }

    public Completions advance(Long owner, long now, Long resolvingSession) {
        Map<Integer, List<String>> finishedBuilds = new LinkedHashMap<>(), finishedResearch = new LinkedHashMap<>();
        Completions completed = new Completions(finishedBuilds, finishedResearch);
        if (eventTime.get() != null) return completed;
        economy.beginEconomyWindow(owner, now);
        Set<String> visited = new HashSet<>();
        try {
            while (true) {
                List<PlayerCity> owned = new ArrayList<>(cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(owner));
                if (owned.stream().noneMatch(c -> c.getCitySlot() == 0)) {
                    PlayerCity main = new PlayerCity(); main.setOwnerId(owner); main.setCitySlot(0); main.setReadyAt(0L);
                    owned.add(main);
                }
                List<Event> events = new ArrayList<>();
                for (PlayerCity city : owned) {
                    if (city.isSurrenderFrozen(now) || city.getReadyAt() > now) continue;
                    CityState state = states.findByPlayerIdAndCitySlot(owner, city.getCitySlot()).orElse(null);
                    if (state != null) {
                        long shield = Objects.requireNonNullElse(state.getShieldUntil(), 0L);
                        long warEnd = Objects.requireNonNullElse(state.getWarEndAt(), 0L);
                        if (shield > 0 && shield <= now) events.add(new Event(shield, 5, (long) city.getCitySlot(), city));
                        if ("war".equals(state.getStatus()) && warEnd > 0 && warEnd <= now)
                            events.add(new Event(warEnd, 6, (long) city.getCitySlot(), city));
                    }
                    // 同时刻经济配置先完成，再结算战斗；事件排序与在线／离线请求顺序无关。
                    construction.findByPlayerIdAndCitySlotAndFinishAtLessThanEqual(owner, city.getCitySlot(), now)
                            .forEach(q -> events.add(new Event(q.getFinishAt(), 0, q.getId(), city)));
                    research.findByPlayerIdAndCitySlotAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(owner, city.getCitySlot(), now)
                            .forEach(q -> events.add(new Event(q.getFinishesAt(), 1, q.getId(), city)));
                    production.findByPlayerIdAndCitySlotAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(owner, city.getCitySlot(), now)
                            .forEach(q -> events.add(new Event(q.getFinishesAt(), 2, q.getId(), city)));
                    // 支援先抵达再锁定同一时刻的来袭；接收城 Tick 可以独立完成交付和计粮起点。
                    // 尚未补建城市实体的旧主城仍结算经济与队列，但没有可关联的驻军或城市战斗。
                    if (city.getId() != null) {
                        marches.findDueSupportArrivals(city.getId().toString(), now)
                                .forEach(m -> events.add(new Event(m.getArriveAt(), 3, m.getId(), city)));
                        battles.findActivePlayerCityBattles(city.getId().toString()).stream()
                                .filter(s -> !Objects.equals(s.getId(), resolvingSession) && s.getRoundDeadlineAt() != null && s.getRoundDeadlineAt() <= now)
                                .forEach(s -> events.add(new Event(s.getRoundDeadlineAt(), 4, s.getId(), city)));
                    }
                }
                Event next = events.stream().filter(e -> !visited.contains(e.kind + ":" + e.id + ":" + e.time))
                        .min(Comparator.comparingLong(Event::time).thenComparingInt(Event::kind).thenComparing(Event::id)).orElse(null);
                if (next == null) return completed;
                visited.add(next.kind + ":" + next.id + ":" + next.time);
                eventTime.set(next.time);
                // 账号科技影响所有接收城市，先统一结清旧配置再执行变化。
                for (PlayerCity city : owned) {
                    if (city.isSurrenderFrozen(now) || city.getReadyAt() > next.time) continue;
                    economy.settleEconomyAt(owner, city.getCitySlot(), next.time);
                }
                try (var ignored = scope.enter(next.city)) {
                    switch (next.kind) {
                        case 0 -> finishedBuilds.computeIfAbsent(next.city.getCitySlot(), k -> new ArrayList<>())
                                .addAll(builds.completeUpgrade(owner, next.time));
                        case 1 -> finishedResearch.computeIfAbsent(next.city.getCitySlot(), k -> new ArrayList<>())
                                .addAll(tech.settleCompletedResearchForCity(owner, next.city.getCitySlot(), next.time));
                        case 2 -> army.completeProduction(owner, next.time);
                        case 3 -> movement.processSupportArrival(next.id, next.time);
                        case 4 -> movement.processTimedOutTacticalBattle(next.id, next.time);
                        case 5, 6 -> { /* 已由同一时刻的经济结算清理到期状态。 */ }
                        default -> throw new IllegalStateException("未知经济事件");
                    }
                }
                eventTime.remove();
            }
        } finally {
            eventTime.remove();
            Completions saved = pending().computeIfAbsent(owner, key -> new Completions(new LinkedHashMap<>(), new LinkedHashMap<>()));
            finishedBuilds.forEach((slot, values) -> saved.builds.computeIfAbsent(slot, key -> new ArrayList<>()).addAll(values));
            finishedResearch.forEach((slot, values) -> saved.research.computeIfAbsent(slot, key -> new ArrayList<>()).addAll(values));
        }
    }
}
