package com.wargame.repository;

import com.wargame.model.entity.March;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarchRepository extends JpaRepository<March, Long> {

    List<March> findByPlayerId(Long playerId);

    List<March> findByTargetKindAndTargetIdAndStationedTrueOrderByIdAsc(String targetKind, String targetId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from March m where m.id = :id")
    java.util.Optional<March> findSupportForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    List<March> findByPlayerIdAndReturning(Long playerId, Boolean returning);

    List<March> findByPlayerIdAndGathering(Long playerId, Boolean gathering);

    /** 仅进攻出征与战斗占用战术；侦察、采集和返程不占用。 */
    @org.springframework.data.jpa.repository.Query("""
            select (count(m) > 0) from March m
            where m.playerId = :playerId
              and (m.returning = false or m.returning is null)
              and (m.gathering = false or m.gathering is null)
              and (m.action in ('conquer', 'plunder', 'feint', 'harass', 'guerrilla') or m.action like 'tactical%' or m.action is null)
            """)
    boolean existsActiveAttack(@org.springframework.data.repository.query.Param("playerId") Long playerId);

    @org.springframework.data.jpa.repository.Query("""
            select m from March m
            where m.targetKind = 'player' and m.targetId in :cityIds
              and m.playerId <> :defenderId
              and (m.returning = false or m.returning is null)
              and (m.gathering = false or m.gathering is null)
              and (m.action in ('conquer', 'plunder', 'feint', 'harass', 'guerrilla', 'scout') or m.action like 'tactical%')
            order by m.arriveAt, m.id
            """)
    List<March> findIncomingPlayerMarches(
            @org.springframework.data.repository.query.Param("defenderId") Long defenderId,
            @org.springframework.data.repository.query.Param("cityIds") List<String> cityIds);

    /**
     * 查询指向玩家城市的全部未完成行军。范围故意宽于地图来袭列表，
     * 劝降还必须拦截同城运输/驻防以及展示查询尚未列出的新 action。
     */
    @org.springframework.data.jpa.repository.Query("""
            select m from March m
            where m.targetKind = 'player' and m.targetId = :cityId
              and (m.returning = false or m.returning is null)
            order by m.arriveAt, m.id
            """)
    List<March> findUnfinishedMarchesTargetingPlayerCity(
            @org.springframework.data.repository.query.Param("cityId") String cityId);

    @org.springframework.data.jpa.repository.Query("""
            select m.id from March m
            where m.targetKind = 'player' and m.targetId = :cityId
              and m.battleId is null
              and (m.returning = false or m.returning is null)
              and (m.gathering = false or m.gathering is null)
              and (m.action in ('conquer', 'plunder', 'feint', 'harass', 'guerrilla') or m.action like 'tactical%')
              and m.arriveAt <= :now
            order by m.arriveAt, m.id
            """)
    List<Long> findWaitingPlayerCityAttackIds(
            @org.springframework.data.repository.query.Param("cityId") String cityId,
            @org.springframework.data.repository.query.Param("now") long now,
            org.springframework.data.domain.Pageable page);

    void deleteByPlayerId(Long playerId);

    List<March> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);

    /** 锁定当前读避免 MySQL 旧快照漏掉刚出发的行军；返程和驻防也阻止传送。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("select m from March m where m.playerId = :playerId and m.citySlot = :slot")
    List<March> findCityTeleportDepartures(@org.springframework.data.repository.query.Param("playerId") Long playerId,
                                         @org.springframework.data.repository.query.Param("slot") int slot);

    /** 已交付并返程的运输不再引用目的地；驻防仍占用目标城市。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("select m from March m where m.targetKind = 'player' and m.targetId = :cityId and (m.returning = false or m.returning is null)")
    List<March> findCityTeleportArrivals(@org.springframework.data.repository.query.Param("cityId") String cityId);
    long countByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
    List<March> findByPlayerIdAndCitySlotAndReturning(Long playerId, Integer citySlot, Boolean returning);
    List<March> findByPlayerIdAndCitySlotAndGathering(Long playerId, Integer citySlot, Boolean gathering);
    boolean existsByTargetIdAndTargetKindIn(String targetId, List<String> targetKinds);
    boolean existsByPlayerIdAndCommanderId(Long playerId, Long commanderId);
}
