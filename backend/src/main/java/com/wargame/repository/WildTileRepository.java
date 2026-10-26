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

    long countByWorldId(Long worldId);

    long countByWorldIdAndOccupiedTrue(Long worldId);

    @org.springframework.data.jpa.repository.Query("select count(w) from WildTile w where w.worldId = :worldId and (w.occupied = false or w.occupied is null) and (w.occupiedBy is null or w.occupiedBy = 0)")
    long countUnoccupiedByWorldId(@org.springframework.data.repository.query.Param("worldId") Long worldId);

    @org.springframework.data.jpa.repository.Query("select w from WildTile w where w.worldId = :worldId and (w.occupied = false or w.occupied is null) and (w.occupiedBy is null or w.occupiedBy = 0)")
    java.util.List<WildTile> findUnoccupiedByWorldId(@org.springframework.data.repository.query.Param("worldId") Long worldId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from WildTile w where w.worldId = :worldId and (w.occupied = false or w.occupied is null) and (w.occupiedBy is null or w.occupiedBy = 0)")
    int deleteUnoccupiedByWorldId(@org.springframework.data.repository.query.Param("worldId") Long worldId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from WildTile w where w.worldId = :worldId and w.x >= :minX and w.x <= :maxX and w.y >= :minY and w.y <= :maxY and (w.occupied = false or w.occupied is null) and (w.occupiedBy is null or w.occupiedBy = 0)")
    int deleteUnoccupiedByWorldIdAndBounds(@org.springframework.data.repository.query.Param("worldId") Long worldId,
                                          @org.springframework.data.repository.query.Param("minX") int minX,
                                          @org.springframework.data.repository.query.Param("maxX") int maxX,
                                          @org.springframework.data.repository.query.Param("minY") int minY,
                                          @org.springframework.data.repository.query.Param("maxY") int maxY);

    @org.springframework.data.jpa.repository.Query("select w.type, count(w) from WildTile w where w.worldId = :worldId group by w.type")
    java.util.List<Object[]> countGroupByTypeByWorldId(@org.springframework.data.repository.query.Param("worldId") Long worldId);

    List<WildTile> findByOccupiedBy(Long occupiedBy);
    List<WildTile> findByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(Long occupiedBy, Integer occupiedCitySlot);
    long countByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(Long occupiedBy, Integer occupiedCitySlot);

    /** 锁定当前领地ID，避免MySQL旧快照漏计及一级缓存旧实体升级锁时的版本冲突。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w.id from WildTile w where w.occupiedBy = :player "
            + "and w.occupiedCitySlot = :city and w.occupied = true order by w.id")
    List<Long> lockOwnedIdsByCity(@org.springframework.data.repository.query.Param("player") Long playerId,
                                @org.springframework.data.repository.query.Param("city") Integer citySlot);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from WildTile w where w.id = :id")
    java.util.Optional<WildTile> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
