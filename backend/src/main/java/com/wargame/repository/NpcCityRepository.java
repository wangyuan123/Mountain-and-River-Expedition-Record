package com.wargame.repository;

import com.wargame.model.entity.NpcCity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NpcCityRepository extends JpaRepository<NpcCity, Long> {

    List<NpcCity> findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(Long worldId, int minX, int maxX, int minY, int maxY);

    List<NpcCity> findByWorldId(Long worldId);

    List<NpcCity> findByWorldIdAndDefeatedFalse(Long worldId);
}
