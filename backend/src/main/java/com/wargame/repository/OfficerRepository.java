package com.wargame.repository;

import com.wargame.model.entity.Officer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OfficerRepository extends JpaRepository<Officer, Long> {

    List<Officer> findByPlayerId(Long playerId);

    List<Officer> findByPlayerIdAndRole(Long playerId, String role);

    void deleteByPlayerId(Long playerId);

    List<Officer> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
    List<Officer> findByPlayerIdAndCitySlotAndRole(Long playerId, Integer citySlot, String role);
}
