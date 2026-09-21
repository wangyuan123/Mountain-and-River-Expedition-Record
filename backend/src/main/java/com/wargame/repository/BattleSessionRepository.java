package com.wargame.repository;

import com.wargame.model.entity.BattleSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BattleSessionRepository extends JpaRepository<BattleSession, Long> {
    Optional<BattleSession> findByMarchId(Long marchId);
    Optional<BattleSession> findByIdAndPlayerId(Long id, Long playerId);
}
