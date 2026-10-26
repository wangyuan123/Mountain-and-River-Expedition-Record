package com.wargame.repository;

import com.wargame.model.entity.ScoutReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;

@Repository
public interface ScoutReportRepository extends JpaRepository<ScoutReport, Long> {

    List<ScoutReport> findByPlayerId(Long playerId);

    List<ScoutReport> findByPlayerIdAndCreatedAtGreaterThanEqual(Long playerId, Long createdAt);

    List<ScoutReport> findByPlayerIdAndPersistedRealAtGreaterThanEqual(Long playerId, Long persistedRealAt);

    List<ScoutReport> findByPlayerIdAndTypeAndTargetXAndTargetYAndDetailExpiresAtGreaterThanOrderByCreatedAtDesc(
            Long playerId, String type, Integer targetX, Integer targetY, Long detailExpiresAt);

    List<ScoutReport> findByPlayerIdOrderByCreatedAtDesc(Long playerId);

    long countByPlayerIdAndReadAt(Long playerId, Long readAt);

    @Query("select count(r) from ScoutReport r where r.playerId = :playerId and (r.readAt = 0 or r.readAt is null)")
    long countUnreadByPlayerId(@Param("playerId") Long playerId);

    @Query("select count(r) from ScoutReport r where r.playerId = :playerId and (r.readAt = 0 or r.readAt is null) " +
            "and (r.starredAt is not null or r.summaryExpiresAt > :now)")
    long countVisibleUnreadByPlayerId(@Param("playerId") Long playerId, @Param("now") long now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ScoutReport r where r.id = :id")
    java.util.Optional<ScoutReport> findByIdForUpdate(@Param("id") Long id);

    long countByPlayerIdAndStarredAtIsNotNull(Long playerId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ScoutReport r set r.readAt = :now where r.playerId = :playerId " +
            "and (r.readAt = 0 or r.readAt is null) and (r.starredAt is not null or r.summaryExpiresAt > :now)")
    int markAllVisibleRead(@Param("playerId") Long playerId, @Param("now") long now);

    void deleteByPlayerId(Long playerId);
}
