package com.wargame.repository;

import com.wargame.model.entity.March;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarchRepository extends JpaRepository<March, Long> {

    List<March> findByPlayerId(Long playerId);

    List<March> findByPlayerIdAndReturning(Long playerId, Boolean returning);

    List<March> findByPlayerIdAndGathering(Long playerId, Boolean gathering);

    @org.springframework.data.jpa.repository.Query("""
            select m from March m
            where m.targetKind = 'player' and m.targetId in :cityIds
              and m.playerId <> :defenderId
              and (m.returning = false or m.returning is null)
              and (m.gathering = false or m.gathering is null)
              and (m.action in ('conquer', 'plunder', 'scout') or m.action like 'tactical%')
            order by m.arriveAt, m.id
            """)
    List<March> findIncomingPlayerMarches(
            @org.springframework.data.repository.query.Param("defenderId") Long defenderId,
            @org.springframework.data.repository.query.Param("cityIds") List<String> cityIds);

    void deleteByPlayerId(Long playerId);

    List<March> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
    List<March> findByPlayerIdAndCitySlotAndReturning(Long playerId, Integer citySlot, Boolean returning);
    List<March> findByPlayerIdAndCitySlotAndGathering(Long playerId, Integer citySlot, Boolean gathering);
    boolean existsByTargetIdAndTargetKindIn(String targetId, List<String> targetKinds);
    boolean existsByPlayerIdAndCommanderId(Long playerId, Long commanderId);
}
