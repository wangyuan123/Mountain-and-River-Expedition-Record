package com.wargame.service;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** 移交成功和系统取消共用的城市解冻时钟；调用方须持有城市及账号锁。 */
@Service
@RequiredArgsConstructor
public class CityFreezeService {
    private final EntityManager em;
    private final CityScope scope;
    private final ResourcesRepository stocks;

    /** 保留冻结时剩余工期、伤兵期限和欠薪宽限，不补算暂停期间的收支。 */
    public void resume(PlayerCity city, long now) {
        Long frozenAt = city.getSurrenderFrozenAt();
        if (frozenAt == null) return;
        long paused = Math.max(0L, now - frozenAt);
        Long owner = city.getOwnerId(); int slot = city.getCitySlot();
        for (Construction q : rows(Construction.class, owner, slot)) {
            q.setStartAt(shift(q.getStartAt(), paused)); q.setFinishAt(shift(q.getFinishAt(), paused));
        }
        for (ArmyProductionQueue q : rows(ArmyProductionQueue.class, owner, slot)) {
            q.setStartedAt(shift(q.getStartedAt(), paused)); q.setFinishesAt(shift(q.getFinishesAt(), paused));
        }
        for (TechResearchQueue q : rows(TechResearchQueue.class, owner, slot)) {
            q.setStartedAt(shift(q.getStartedAt(), paused)); q.setFinishesAt(shift(q.getFinishesAt(), paused));
        }
        for (WoundedUnit w : rows(WoundedUnit.class, owner, slot)) {
            if (w.getExpiresAt() > frozenAt) w.setExpiresAt(Math.addExact(w.getExpiresAt(), paused));
        }
        stocks.findByPlayerIdAndCitySlot(owner, slot).ifPresent(stock -> {
            if (stock.getSalaryArrearsStartedAt() != null) {
                long arrearsStart = stock.getSalaryArrearsStartedAt();
                stock.setSalaryArrearsStartedAt(shift(arrearsStart, paused));
                for (Officer o : rows(Officer.class, owner, slot)) {
                    long eligible = Math.max(arrearsStart,
                            Objects.requireNonNullElse(o.getSalaryGraceFrom(), Objects.requireNonNullElse(o.getRecruitAt(), frozenAt)));
                    // 仅移动薪资用的虚拟起点，招募时间仍保留真实的业务事实。
                    o.setSalaryGraceFrom(Math.addExact(eligible, paused));
                }
            }
        });
        if (city.getGarrisonFoodAt() != null) city.setGarrisonFoodAt(now);
        if (slot != 0 || em.find(Player.class, owner) != null) {
            try (var ignored = scope.enter(city)) {
                scope.economy(owner).setLastTick(now); scope.saveEconomy(owner);
            }
        }
        city.setLastTick(now);
        // 恢复与清除标记属于同一次操作，重复恢复不能再次顺延工期。
        city.setSurrenderOfferId(null);
        city.setSurrenderRecipientId(null);
        city.setSurrenderFrozenAt(null);
        city.setSurrenderTransferDeadlineAt(null);
    }

    private static Long shift(Long time, long paused) { return time == null ? null : Math.addExact(time, paused); }
    private <T extends CityOwnedEntity> java.util.List<T> rows(Class<T> type, Long owner, int slot) {
        return em.createQuery("select r from " + type.getSimpleName() + " r where r.playerId = :owner and r.citySlot = :slot", type)
                .setParameter("owner", owner).setParameter("slot", slot).getResultList();
    }
}
