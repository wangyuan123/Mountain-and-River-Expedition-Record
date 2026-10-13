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

    /** 占领校验使用当前已完成市政厅等级，不依赖请求早先读取的快照。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b.id from Building b where b.playerId = :player "
            + "and b.citySlot = :city and b.type = 'command'")
    List<Long> lockCityHallIds(@org.springframework.data.repository.query.Param("player") Long playerId,
                              @org.springframework.data.repository.query.Param("city") Integer citySlot);
}
