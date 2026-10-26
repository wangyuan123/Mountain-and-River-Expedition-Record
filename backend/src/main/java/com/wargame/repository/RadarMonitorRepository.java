package com.wargame.repository;

import com.wargame.model.entity.RadarMonitor;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface RadarMonitorRepository extends JpaRepository<RadarMonitor, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from RadarMonitor m where m.playerId = :player")
    Optional<RadarMonitor> findForUpdate(@Param("player") Long playerId);
}
