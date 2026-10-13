package com.wargame.repository;

import com.wargame.model.entity.PrisonerUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PrisonerUnitRepository extends JpaRepository<PrisonerUnit, Long> {
    List<PrisonerUnit> findByPlayerIdAndCityIdOrderByCreatedAtAscIdAsc(Long playerId, Long cityId);
    void deleteByPlayerId(Long playerId);
}
