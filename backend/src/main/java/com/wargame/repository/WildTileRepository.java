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
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from WildTile w where w.id = :id")
    java.util.Optional<WildTile> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
