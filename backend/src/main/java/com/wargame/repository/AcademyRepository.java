package com.wargame.repository;

import com.wargame.model.entity.Academy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AcademyRepository extends JpaRepository<Academy, Long> {

    Optional<Academy> findByPlayerId(Long playerId);

    void deleteByPlayerId(Long playerId);

    Optional<Academy> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
}
