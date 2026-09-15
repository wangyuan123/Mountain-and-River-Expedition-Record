package com.wargame.repository;

import com.wargame.model.entity.ArmyUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArmyUnitRepository extends JpaRepository<ArmyUnit, Long> {

    List<ArmyUnit> findByPlayerId(Long playerId);

    List<ArmyUnit> findByPlayerIdAndType(Long playerId, String type);

    void deleteByPlayerId(Long playerId);

    List<ArmyUnit> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
    List<ArmyUnit> findByPlayerIdAndCitySlotAndType(Long playerId, Integer citySlot, String type);
}
