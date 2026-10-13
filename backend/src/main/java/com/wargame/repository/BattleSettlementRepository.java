package com.wargame.repository;

import com.wargame.model.entity.BattleSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BattleSettlementRepository extends JpaRepository<BattleSettlement, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<BattleSettlement> findBySourceKindAndSourceId(String sourceKind, String sourceId);
    void deleteByPlayerId(Long playerId);
}
