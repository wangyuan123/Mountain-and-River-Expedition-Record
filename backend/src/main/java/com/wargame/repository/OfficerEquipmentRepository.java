package com.wargame.repository;

import com.wargame.model.entity.OfficerEquipment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OfficerEquipmentRepository extends JpaRepository<OfficerEquipment, Long> {
    List<OfficerEquipment> findByPlayerId(Long playerId);
    List<OfficerEquipment> findByPlayerIdAndOfficerId(Long playerId, Long officerId);
    void deleteByPlayerId(Long playerId);
}
