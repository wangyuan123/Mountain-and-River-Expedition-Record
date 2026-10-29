package com.wargame.repository;

import com.wargame.model.entity.RankGiftReward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface RankGiftRewardRepository extends JpaRepository<RankGiftReward, Long> {
    List<RankGiftReward> findByPlayerIdOrderByRankTierAsc(Long playerId);

    /** 持账号锁后读取最新回执，防止可重复读快照重复发放或开启。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RankGiftReward r where r.playerId = :playerId and r.rankTier = :tier")
    Optional<RankGiftReward> findForUpdate(@Param("playerId") Long playerId, @Param("tier") int tier);
}
