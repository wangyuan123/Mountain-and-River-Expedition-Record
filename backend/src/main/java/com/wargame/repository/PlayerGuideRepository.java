package com.wargame.repository;

import com.wargame.model.entity.PlayerGuide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerGuideRepository extends JpaRepository<PlayerGuide, PlayerGuide.PK> {

    List<PlayerGuide> findByPlayerId(Long playerId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select g from PlayerGuide g where g.playerId = :playerId")
    List<PlayerGuide> lockByPlayerId(@org.springframework.data.repository.query.Param("playerId") Long playerId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select g from PlayerGuide g where g.playerId = :playerId and g.stepId = :stepId")
    Optional<PlayerGuide> lockEntry(@org.springframework.data.repository.query.Param("playerId") Long playerId,
                                   @org.springframework.data.repository.query.Param("stepId") String stepId);

    Optional<PlayerGuide> findByPlayerIdAndStepId(Long playerId, String stepId);

    void deleteByPlayerId(Long playerId);
}
