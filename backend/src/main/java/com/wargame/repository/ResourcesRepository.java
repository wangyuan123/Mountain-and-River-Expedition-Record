package com.wargame.repository;

import com.wargame.model.entity.Resources;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResourcesRepository extends JpaRepository<Resources, Long> {

    @org.springframework.data.jpa.repository.Query(value = "select r.* from resources r where " +
            "r.food > :limit or r.steel > :limit or r.oil > :limit or r.rare > :limit or r.gold > :limit " +
            "or exists (select 1 from resource_overflow_state s where s.resource_id = r.id) " +
            "order by r.player_id, r.city_slot, r.id for update", nativeQuery = true)
    java.util.List<Resources> findAllForOverflowTreatment(@org.springframework.data.repository.query.Param("limit") long limit);

    Optional<Resources> findByPlayerId(Long playerId);

    Optional<Resources> findFirstByPlayerIdOrderByCitySlotAsc(Long playerId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Resources r where r.playerId = :playerId")
    Optional<Resources> findForTreatment(@org.springframework.data.repository.query.Param("playerId") Long playerId);

    void deleteByPlayerId(Long playerId);

    Optional<Resources> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Resources r where r.playerId = :playerId and r.citySlot = :citySlot")
    Optional<Resources> findCityForTreatment(@org.springframework.data.repository.query.Param("playerId") Long playerId, @org.springframework.data.repository.query.Param("citySlot") Integer citySlot);
}
