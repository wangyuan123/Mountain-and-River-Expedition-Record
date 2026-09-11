package com.wargame.repository;

import com.wargame.model.entity.ScoutReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScoutReportRepository extends JpaRepository<ScoutReport, Long> {

    List<ScoutReport> findByPlayerId(Long playerId);

    List<ScoutReport> findByPlayerIdOrderByCreatedAtDesc(Long playerId);

    long countByPlayerIdAndReadAt(Long playerId, Long readAt);

    void deleteByPlayerId(Long playerId);
}
