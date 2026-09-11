package com.wargame.repository;

import com.wargame.model.entity.PlayerQuest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerQuestRepository extends JpaRepository<PlayerQuest, Long> {

    List<PlayerQuest> findByPlayerId(Long playerId);

    List<PlayerQuest> findByPlayerIdAndStatus(Long playerId, String status);

    Optional<PlayerQuest> findByPlayerIdAndQuestId(Long playerId, String questId);

    @Query("SELECT q FROM PlayerQuest q WHERE q.playerId = :playerId AND q.questId IN :ids")
    List<PlayerQuest> findByPlayerIdAndQuestIdIn(@Param("playerId") Long playerId,
                                                  @Param("ids") List<String> ids);

    void deleteByPlayerId(Long playerId);
}
