package com.wargame.repository;
import com.wargame.model.entity.UnitLoadout;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UnitLoadoutRepository extends JpaRepository<UnitLoadout, Long> {
    java.util.List<UnitLoadout> findByPlayerId(Long playerId);
    java.util.Optional<UnitLoadout> findByPlayerIdAndUnitType(Long playerId, String unit);
    void deleteByPlayerId(Long playerId);
}
