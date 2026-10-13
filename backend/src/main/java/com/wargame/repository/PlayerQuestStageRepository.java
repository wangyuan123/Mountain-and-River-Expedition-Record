package com.wargame.repository;

import com.wargame.model.entity.PlayerQuestStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerQuestStageRepository extends JpaRepository<PlayerQuestStage, Long> {

    List<PlayerQuestStage> findByPlayerId(Long playerId);

    Optional<PlayerQuestStage> findByPlayerIdAndStageId(Long playerId, String stageId);

    void deleteByPlayerId(Long playerId);
}
