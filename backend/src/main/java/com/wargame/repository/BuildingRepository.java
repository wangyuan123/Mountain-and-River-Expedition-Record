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
}
