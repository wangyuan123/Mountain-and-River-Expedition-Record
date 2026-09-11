package com.wargame.repository;

import com.wargame.model.entity.ArmyProductionQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ArmyProductionQueueRepository extends JpaRepository<ArmyProductionQueue, Long> {
    List<ArmyProductionQueue> findByPlayerIdOrderByStartedAtAscIdAsc(Long playerId);
    List<ArmyProductionQueue> findByPlayerIdAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(Long playerId, Long finishesAt);
    long countByPlayerId(Long playerId);
}
