package com.wargame.repository;
import com.wargame.model.entity.PvpRoundOrder;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface PvpRoundOrderRepository extends JpaRepository<PvpRoundOrder, String> {
    Optional<PvpRoundOrder> findBySessionIdAndRoundNoAndPlayerId(String session, int round, Long player);
}
