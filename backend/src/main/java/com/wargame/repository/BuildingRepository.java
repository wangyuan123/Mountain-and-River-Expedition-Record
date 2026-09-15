package com.wargame.repository;

import com.wargame.model.entity.Building;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BuildingRepository extends JpaRepository<Building, Long> {

    List<Building> findByPlayerId(Long playerId);

    List<Building> findByPlayerIdAndType(Long playerId, String type);

    List<Building> findByPlayerIdAndTypeOrderByIdAsc(Long playerId, String type);

    void deleteByPlayerId(Long playerId);

    List<Building> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
    List<Building> findByPlayerIdAndCitySlotAndType(Long playerId, Integer citySlot, String type);
    List<Building> findByPlayerIdAndCitySlotAndTypeOrderByIdAsc(Long playerId, Integer citySlot, String type);
}
