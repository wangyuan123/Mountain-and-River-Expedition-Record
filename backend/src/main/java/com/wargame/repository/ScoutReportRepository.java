package com.wargame.repository;

import com.wargame.model.entity.ScoutReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScoutReportRepository extends JpaRepository<ScoutReport, Long> {

    List<ScoutReport> findByPlayerId(Long playerId);

    List<ScoutReport> findByPlayerIdAndCreatedAtGreaterThanEqual(Long playerId, Long createdAt);

    List<ScoutReport> findByPlayerIdOrderByCreatedAtDesc(Long playerId);

    long countByPlayerIdAndReadAt(Long playerId, Long readAt);

    @Query("select count(r) from ScoutReport r where r.playerId = :playerId and (r.readAt = 0 or r.readAt is null)")
    long countUnreadByPlayerId(@Param("playerId") Long playerId);

    void deleteByPlayerId(Long playerId);
}
