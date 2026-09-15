package com.wargame.repository;

import com.wargame.model.entity.WoundedUnit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface WoundedUnitRepository extends JpaRepository<WoundedUnit, Long> {
    List<WoundedUnit> findByPlayerIdAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(Long playerId, Long now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WoundedUnit w where w.id = :id and w.playerId = :playerId")
    Optional<WoundedUnit> findForTreatment(@Param("playerId") Long playerId, @Param("id") Long id);

    @Modifying
    @Query("delete from WoundedUnit w where w.expiresAt <= :now")
    int deleteExpired(@Param("now") long now);

    void deleteByPlayerId(Long playerId);

    List<WoundedUnit> findByPlayerIdAndCitySlotAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(Long playerId, Integer citySlot, Long now);
}
