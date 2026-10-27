package com.wargame.repository;

import com.wargame.model.entity.PlayerVipReward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerVipRewardRepository extends JpaRepository<PlayerVipReward, Long> {
    List<PlayerVipReward> findByPlayerId(Long playerId);
    Optional<PlayerVipReward> findByPlayerIdAndVipLevel(Long playerId, Integer vipLevel);
    boolean existsByPlayerIdAndVipLevel(Long playerId, Integer vipLevel);
}
