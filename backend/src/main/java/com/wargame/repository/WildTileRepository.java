package com.wargame.repository;

import com.wargame.model.entity.WildTile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WildTileRepository extends JpaRepository<WildTile, Long> {

    List<WildTile> findByWorldId(Long worldId);

    List<WildTile> findByWorldIdAndOccupiedTrue(Long worldId);

    List<WildTile> findByOccupiedBy(Long occupiedBy);
}
