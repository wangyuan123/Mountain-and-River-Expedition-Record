package com.wargame.repository;

import com.wargame.model.entity.TechResearchQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TechResearchQueueRepository extends JpaRepository<TechResearchQueue, Long> {
    List<TechResearchQueue> findByPlayerIdOrderByStartedAtAscIdAsc(Long playerId);
    List<TechResearchQueue> findByPlayerIdAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(Long playerId, Long finishesAt);
    List<TechResearchQueue> findByPlayerIdAndCitySlotOrderByStartedAtAscIdAsc(Long playerId, Integer citySlot);
    void deleteByPlayerId(Long playerId);
}
