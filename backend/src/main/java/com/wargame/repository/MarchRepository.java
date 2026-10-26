package com.wargame.repository;

import com.wargame.model.entity.March;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarchRepository extends JpaRepository<March, Long> {

    List<March> findByPlayerId(Long playerId);

    List<March> findByPlayerIdInAndStationedTrueAndTargetKind(java.util.Collection<Long> players, String targetKind);

    @org.springframework.data.jpa.repository.Query("""
            select m from March m where
              ((m.targetKind = 'player' and m.targetId in :cities)
                or (m.targetKind = 'wild' and m.targetId in :wilds))
              and (m.returning = false or m.returning is null)
              and (m.stationed = false or m.stationed is null)
              and (m.gathering = false or m.gathering is null)
              and (m.action in ('conquer','plunder','feint','harass','guerrilla','scout') or m.action is null)
            order by m.id
            """)
    List<March> findRegionalThreats(@org.springframework.data.repository.query.Param("cities") java.util.Collection<String> cities,
                                    @org.springframework.data.repository.query.Param("wilds") java.util.Collection<String> wilds);

    /** 查询拦截关系的账号连通分量，双方 Tick 和操作在写入前按账号 ID 统一加锁。 */
    @org.springframework.data.jpa.repository.Query("""
            select i from March i, March e
            where i.targetKind = 'incoming_march' and i.targetId = cast(e.id as string)
              and i.action = 'intercept_scout' and (i.returning = false or i.returning is null)
              and (i.playerId in :players or e.playerId in :players)
            """)
    List<March> findRelatedInterceptions(@org.springframework.data.repository.query.Param("players") List<Long> players);


    /** 只查询明确指向我方在途行军的敌方截击，返程截击和旧来袭表 ID 不参与匹配。 */
    @org.springframework.data.jpa.repository.Query("""
            select m from March m
            where m.targetKind = 'incoming_march' and m.targetId in :marchIds
              and m.action = 'intercept_scout'
              and (m.returning = false or m.returning is null)
            order by m.arriveAt, m.id
            """)
    List<March> findActiveInterceptions(
            @org.springframework.data.repository.query.Param("marchIds") List<String> marchIds);

    List<March> findByTargetKindAndTargetIdAndStationedTrueOrderByIdAsc(String targetKind, String targetId);

    /** 接收方离线结算也处理到期支援，不依赖派兵人先上线或先被调度。 */
    @org.springframework.data.jpa.repository.Query("""
            select m from March m where m.targetKind = 'player' and m.targetId = :cityId
              and m.action in ('transport', 'rebase', 'reinforce')
              and (m.returning = false or m.returning is null)
              and (m.stationed = false or m.stationed is null)
              and m.displaced = false and m.arriveAt <= :now
            order by m.arriveAt, m.id
            """)
    List<March> findDueSupportArrivals(@org.springframework.data.repository.query.Param("cityId") String cityId,
                                      @org.springframework.data.repository.query.Param("now") long now);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    /** 只锁行 ID，避免关联发现阶段缓存的旧版本在升级实体锁时先触发乐观冲突。 */
    @org.springframework.data.jpa.repository.Query("select m.id from March m where m.id = :id")
    java.util.Optional<Long> lockSupportId(@org.springframework.data.repository.query.Param("id") Long id);

    List<March> findByPlayerIdAndReturning(Long playerId, Boolean returning);

    List<March> findByPlayerIdAndGathering(Long playerId, Boolean gathering);

    List<March> findByTargetKindAndTargetId(String targetKind, String targetId);

    /** 当前读检查各城市尚未召回的采集队及未结算返程，避免手动废弃导致兵力或资源丢失。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("""
            select m.id from March m
            where m.targetKind = 'wild_gather' and m.targetId = :targetId
              and (m.returning = false or m.returning is null
                   or m.gatherResourceSettled = false or m.gatherResourceSettled is null)
            order by m.id
            """)
    List<Long> findWildGatherReleaseBlockerIds(@org.springframework.data.repository.query.Param("targetId") String targetId);

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

    /** 仅公开护盾拦截后的敌军返程；原目标 ID 保留为守方城市，不暴露其他撤军路线。 */
    @org.springframework.data.jpa.repository.Query("""
            select m from March m
            where m.targetKind = 'player' and m.targetId in :cityIds
              and m.playerId <> :defenderId
              and m.returning = true
              and m.returnReason = 'shield'
              and m.battleId is null
              and (m.action in ('conquer', 'plunder', 'feint', 'harass', 'guerrilla', 'scout') or m.action like 'tactical%')
            order by m.arriveAt, m.id
            """)
    List<March> findReturningEnemyPlayerMarches(
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
