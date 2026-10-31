package com.wargame.repository;

import com.wargame.model.entity.OfficerEquipmentProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface OfficerEquipmentProgressRepository extends JpaRepository<OfficerEquipmentProgress, Long> {
    List<OfficerEquipmentProgress> findByPlayerId(Long playerId);
    Optional<OfficerEquipmentProgress> findByPlayerIdAndProgressKey(Long playerId, String key);
    List<OfficerEquipmentProgress> findByPlayerIdAndProgressKeyStartingWith(Long playerId, String prefix);
    @org.springframework.data.jpa.repository.Query("select r from OfficerEquipmentProgress r where r.playerId=:player and (r.progressKey like 'collection/%' or r.progressKey like 'achievement/%' or r.progressKey like 'loadout/%')")
    List<OfficerEquipmentProgress> displayRows(@org.springframework.data.repository.query.Param("player") Long player);
    void deleteByPlayerId(Long playerId);
}
