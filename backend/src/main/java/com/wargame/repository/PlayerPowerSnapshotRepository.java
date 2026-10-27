package com.wargame.repository;

import com.wargame.model.entity.PlayerPowerSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import java.util.List;

public interface PlayerPowerSnapshotRepository extends JpaRepository<PlayerPowerSnapshot, Long> {
    PlayerPowerSnapshot findFirstByPlayerIdOrderByObservedAtDescIdDesc(Long playerId);
    @Query("select s.playerId, max(s.formationPower), max(s.overallPower) from PlayerPowerSnapshot s where s.observedAt > :since and s.playerId in :players group by s.playerId")
    List<Object[]> peaks(List<Long> players, long since);
    @Modifying
    @Query("delete from PlayerPowerSnapshot s where s.observedAt <= :since")
    void deleteByObservedAtLessThanEqual(long since);
}
