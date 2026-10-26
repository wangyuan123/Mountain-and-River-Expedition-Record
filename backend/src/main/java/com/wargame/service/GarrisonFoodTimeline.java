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
    @Autowired @Lazy private GarrisonFoodService food;
    @Autowired @Lazy private BuildService builds;
    @Autowired @Lazy private TechService tech;
    @Autowired @Lazy private ArmyService army;
    @Autowired @Lazy private MarchService movement;
    private final ThreadLocal<Long> eventTime = new ThreadLocal<>();
    public Long eventTime() { return eventTime.get(); }
    private record Event(long time, int kind, Long id, PlayerCity city) {}

    /** 调用方已经取得参与者账号锁；当前正在执行的回合不得再次递归推进。 */
    public void advance(Long owner, long now, Long resolvingSession) {
        if (eventTime.get() != null) return;
        Set<String> visited = new HashSet<>();
        try {
            while (true) {
                List<PlayerCity> owned = cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(owner);
                if (owned.stream().noneMatch(food::managed) && owned.stream()
                        .noneMatch(c -> !marches.findDueSupportArrivals(c.getId().toString(), now).isEmpty())) return;
                List<Event> events = new ArrayList<>();
                for (PlayerCity city : owned) {
                    if (city.isSurrenderFrozen(now) || city.getReadyAt() > now) continue;
                    // 同时刻经济配置先完成，再结算战斗；事件排序与在线／离线请求顺序无关。
                    construction.findByPlayerIdAndCitySlotAndFinishAtLessThanEqual(owner, city.getCitySlot(), now)
                            .forEach(q -> events.add(new Event(q.getFinishAt(), 0, q.getId(), city)));
                    research.findByPlayerIdAndCitySlotAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(owner, city.getCitySlot(), now)
                            .forEach(q -> events.add(new Event(q.getFinishesAt(), 1, q.getId(), city)));
                    production.findByPlayerIdAndCitySlotAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(owner, city.getCitySlot(), now)
                            .forEach(q -> events.add(new Event(q.getFinishesAt(), 2, q.getId(), city)));
                    // 支援先抵达再锁定同一时刻的来袭；接收城 Tick 可以独立完成交付和计粮起点。
                    marches.findDueSupportArrivals(city.getId().toString(), now)
                            .forEach(m -> events.add(new Event(m.getArriveAt(), 3, m.getId(), city)));
                    battles.findActivePlayerCityBattles(city.getId().toString()).stream()
                            .filter(s -> !Objects.equals(s.getId(), resolvingSession) && s.getRoundDeadlineAt() != null && s.getRoundDeadlineAt() <= now)
                            .forEach(s -> events.add(new Event(s.getRoundDeadlineAt(), 4, s.getId(), city)));
                }
                Event next = events.stream().filter(e -> !visited.contains(e.kind + ":" + e.id + ":" + e.time))
                        .min(Comparator.comparingLong(Event::time).thenComparingInt(Event::kind).thenComparing(Event::id)).orElse(null);
                if (next == null) return;
                visited.add(next.kind + ":" + next.id + ":" + next.time);
                eventTime.set(next.time);
                // 账号科技影响所有接收城市，先统一结清旧配置再执行变化。
                for (PlayerCity city : owned) food.settle(city, next.time);
                try (var ignored = scope.enter(next.city)) {
                    switch (next.kind) {
                        case 0 -> builds.completeUpgrade(owner, next.time);
                        case 1 -> tech.settleCompletedResearchForCity(owner, next.city.getCitySlot(), next.time);
                        case 2 -> army.completeProduction(owner, next.time);
                        case 3 -> movement.processSupportArrival(next.id, next.time);
                        case 4 -> movement.processTimedOutTacticalBattle(next.id, next.time);
                        default -> throw new IllegalStateException("未知粮耗事件");
                    }
                }
                eventTime.remove();
            }
        } finally { eventTime.remove(); }
    }
}
