package com.wargame.repository;
import com.wargame.model.entity.UnitEquipmentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UnitEquipmentOrderRepository extends JpaRepository<UnitEquipmentOrder, Long> {
    java.util.List<UnitEquipmentOrder> findByPlayerIdAndCitySlotOrderByStartAtAscIdAsc(Long playerId, int citySlot);
    java.util.List<UnitEquipmentOrder> findByPlayerId(Long playerId);
    void deleteByPlayerId(Long playerId);
}
