package com.wargame.repository;

import com.wargame.model.entity.TacticalBattleOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TacticalBattleOutcomeRepository extends JpaRepository<TacticalBattleOutcome, Long> {
    void deleteByExpiresAtLessThanEqual(Long now);
}
