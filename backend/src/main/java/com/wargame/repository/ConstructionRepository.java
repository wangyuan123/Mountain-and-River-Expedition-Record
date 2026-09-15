package com.wargame.repository;

import com.wargame.model.entity.Construction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConstructionRepository extends JpaRepository<Construction, Long> {

    List<Construction> findByPlayerId(Long playerId);

    List<Construction> findByPlayerIdAndFinishAtLessThanEqual(Long playerId, Long finishAt);

    List<Construction> findByPlayerIdAndBuildingTypeAndSlot(Long playerId, String buildingType, Integer slot);

    void deleteByPlayerId(Long playerId);

    List<Construction> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
    List<Construction> findByPlayerIdAndCitySlotAndFinishAtLessThanEqual(Long playerId, Integer citySlot, Long finishAt);
    List<Construction> findByPlayerIdAndCitySlotAndBuildingTypeAndSlot(Long playerId, Integer citySlot, String buildingType, Integer slot);
}
