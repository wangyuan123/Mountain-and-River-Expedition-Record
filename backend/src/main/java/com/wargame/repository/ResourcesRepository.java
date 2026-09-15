package com.wargame.repository;

import com.wargame.model.entity.Resources;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResourcesRepository extends JpaRepository<Resources, Long> {

    Optional<Resources> findByPlayerId(Long playerId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Resources r where r.playerId = :playerId")
    Optional<Resources> findForTreatment(@org.springframework.data.repository.query.Param("playerId") Long playerId);

    void deleteByPlayerId(Long playerId);

    Optional<Resources> findByPlayerIdAndCitySlot(Long playerId, Integer citySlot);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Resources r where r.playerId = :playerId and r.citySlot = :citySlot")
    Optional<Resources> findCityForTreatment(@org.springframework.data.repository.query.Param("playerId") Long playerId, @org.springframework.data.repository.query.Param("citySlot") Integer citySlot);
}
