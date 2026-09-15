package com.wargame.repository;

import com.wargame.model.entity.CityState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CityStateRepository extends JpaRepository<CityState, Long> {

    Optional<CityState> findByPlayerId(Long playerId);

    void deleteByPlayerId(Long playerId);

    Optional<CityState> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
}
