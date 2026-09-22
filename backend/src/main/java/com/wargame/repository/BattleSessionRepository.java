package com.wargame.repository;

import com.wargame.model.entity.BattleSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BattleSessionRepository extends JpaRepository<BattleSession, Long> {
    Optional<BattleSession> findByMarchId(Long marchId);
    Optional<BattleSession> findByIdAndPlayerId(Long id, Long playerId);

    /**
     * 手动指挥与定时自动执行必须串行处理同一回合，避免重复结算或乐观锁异常。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BattleSession s where s.id = :id")
    Optional<BattleSession> findForRoundResolution(@Param("id") Long id);

    @Query("select s.id from BattleSession s where s.roundDeadlineAt is null or s.roundDeadlineAt <= :now order by s.id")
    List<Long> findDueRoundIds(@Param("now") long now, org.springframework.data.domain.Pageable page);

    /** The session has already been locked for this round, so no version check is needed at final settlement. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from BattleSession s where s.id = :id")
    int deleteResolvedById(@Param("id") Long id);
}
