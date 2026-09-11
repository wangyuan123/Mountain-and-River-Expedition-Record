package com.wargame.repository;

import com.wargame.model.entity.Technology;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TechnologyRepository extends JpaRepository<Technology, Long> {

    List<Technology> findByPlayerId(Long playerId);

    List<Technology> findByPlayerIdAndType(Long playerId, String type);

    void deleteByPlayerId(Long playerId);
}
