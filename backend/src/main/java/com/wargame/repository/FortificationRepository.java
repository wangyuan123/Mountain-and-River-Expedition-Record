package com.wargame.repository;

import com.wargame.model.entity.Fortification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FortificationRepository extends JpaRepository<Fortification, Long> {

    List<Fortification> findByPlayerId(Long playerId);

    List<Fortification> findByPlayerIdAndType(Long playerId, String type);

    void deleteByPlayerId(Long playerId);
}
