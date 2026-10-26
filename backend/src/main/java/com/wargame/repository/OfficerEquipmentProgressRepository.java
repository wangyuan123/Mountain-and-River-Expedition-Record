package com.wargame.repository;

import com.wargame.model.entity.OfficerEquipmentProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface OfficerEquipmentProgressRepository extends JpaRepository<OfficerEquipmentProgress, Long> {
    List<OfficerEquipmentProgress> findByPlayerId(Long playerId);
    Optional<OfficerEquipmentProgress> findByPlayerIdAndProgressKey(Long playerId, String key);
    List<OfficerEquipmentProgress> findByPlayerIdAndProgressKeyStartingWith(Long playerId, String prefix);
    @org.springframework.data.jpa.repository.Query("select r from OfficerEquipmentProgress r where r.playerId=:player and (r.progressKey like 'collection/%' or r.progressKey like 'achievement/%' or r.progressKey like 'prep/%' or r.progressKey like 'loadout/%' or r.progressKey like :daily or r.progressKey like :weekly)")
    List<OfficerEquipmentProgress> displayRows(@org.springframework.data.repository.query.Param("player") Long player,
            @org.springframework.data.repository.query.Param("daily") String daily, @org.springframework.data.repository.query.Param("weekly") String weekly);
    void deleteByPlayerId(Long playerId);
}
