package com.wargame.repository;

import com.wargame.model.entity.PlayerGuide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerGuideRepository extends JpaRepository<PlayerGuide, PlayerGuide.PK> {

    List<PlayerGuide> findByPlayerId(Long playerId);

    Optional<PlayerGuide> findByPlayerIdAndStepId(Long playerId, String stepId);

    void deleteByPlayerId(Long playerId);
}
