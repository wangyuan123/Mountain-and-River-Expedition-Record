package com.wargame.repository;

import com.wargame.model.entity.March;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarchRepository extends JpaRepository<March, Long> {

    List<March> findByPlayerId(Long playerId);

    List<March> findByPlayerIdAndReturning(Long playerId, Boolean returning);

    List<March> findByPlayerIdAndGathering(Long playerId, Boolean gathering);

    void deleteByPlayerId(Long playerId);
}
