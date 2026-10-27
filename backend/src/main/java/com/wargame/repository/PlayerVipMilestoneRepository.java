package com.wargame.repository;

import com.wargame.model.entity.PlayerVipMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerVipMilestoneRepository extends JpaRepository<PlayerVipMilestone, Long> {
    List<PlayerVipMilestone> findByPlayerId(Long playerId);
    Optional<PlayerVipMilestone> findByPlayerIdAndMilestoneCents(Long playerId, Long milestoneCents);
    boolean existsByPlayerIdAndMilestoneCents(Long playerId, Long milestoneCents);
}
