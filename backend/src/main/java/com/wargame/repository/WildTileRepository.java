package com.wargame.repository;

import com.wargame.model.entity.WildTile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WildTileRepository extends JpaRepository<WildTile, Long> {

    List<WildTile> findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(Long worldId, int minX, int maxX, int minY, int maxY);

    List<WildTile> findByWorldId(Long worldId);

    List<WildTile> findByWorldIdAndOccupiedTrue(Long worldId);

    List<WildTile> findByOccupiedBy(Long occupiedBy);
    List<WildTile> findByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(Long occupiedBy, Integer occupiedCitySlot);
    long countByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(Long occupiedBy, Integer occupiedCitySlot);

    /** 锁定当前领地ID，避免MySQL旧快照漏计及一级缓存旧实体升级锁时的版本冲突。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w.id from WildTile w where w.occupiedBy = :player "
            + "and w.occupiedCitySlot = :city and w.occupied = true order by w.id")
    List<Long> lockOwnedIdsByCity(@org.springframework.data.repository.query.Param("player") Long playerId,
                                @org.springframework.data.repository.query.Param("city") Integer citySlot);
    List<WildTile> findByOccupiedByAndGatherCitySlotAndGatheringTrueAndGatherMode(
            Long occupiedBy, Integer gatherCitySlot, String gatherMode);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from WildTile w where w.id = :id")
    java.util.Optional<WildTile> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
