package com.wargame.repository;

import com.wargame.model.entity.PrisonerOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PrisonerOperationRepository extends JpaRepository<PrisonerOperation, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<PrisonerOperation> findByPlayerIdAndOperationKey(Long playerId, String operationKey);
    void deleteByPlayerId(Long playerId);
}
