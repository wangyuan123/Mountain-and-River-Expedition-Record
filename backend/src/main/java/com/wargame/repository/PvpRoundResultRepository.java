package com.wargame.repository;
import com.wargame.model.entity.PvpRoundResult;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface PvpRoundResultRepository extends JpaRepository<PvpRoundResult, String> {
    List<PvpRoundResult> findBySessionIdOrderByRoundNoAsc(String session);
    boolean existsBySessionIdAndRoundNo(String session, int round);
}
