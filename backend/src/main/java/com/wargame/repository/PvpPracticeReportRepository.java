package com.wargame.repository;
import com.wargame.model.entity.PvpPracticeReport;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface PvpPracticeReportRepository extends JpaRepository<PvpPracticeReport, String> {
    List<PvpPracticeReport> findByPlayerIdOrderByCreatedAtDesc(Long player, Pageable page);
    Optional<PvpPracticeReport> findBySessionIdAndPlayerId(String session, Long player);
}
