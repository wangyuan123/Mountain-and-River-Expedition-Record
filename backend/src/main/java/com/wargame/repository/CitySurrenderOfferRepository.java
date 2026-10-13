package com.wargame.repository;

import com.wargame.model.entity.CitySurrenderOffer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface CitySurrenderOfferRepository extends JpaRepository<CitySurrenderOffer, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CitySurrenderOffer o where o.id = :id")
    Optional<CitySurrenderOffer> findForUpdate(@Param("id") Long id);

    Optional<CitySurrenderOffer> findFirstByCityIdAndStatusIn(Long cityId, List<String> statuses);
    List<CitySurrenderOffer> findByDefenderIdAndStatusOrderBySentAtDesc(Long defenderId, String status);
    List<CitySurrenderOffer> findByInitiatorIdOrderBySentAtDesc(Long initiatorId);

    @Query("select o from CitySurrenderOffer o where o.status = 'PENDING' and o.responseExpiresAt <= :now order by o.id")
    List<CitySurrenderOffer> findPendingDue(@Param("now") long now, Pageable page);

    @Query("select o from CitySurrenderOffer o where o.status = 'ACCEPTED' and o.transferDeadlineAt <= :now order by o.id")
    List<CitySurrenderOffer> findAcceptedDue(@Param("now") long now, Pageable page);

    @Query("select count(o) from CitySurrenderOffer o where o.initiatorId = :playerId and o.status = 'ACCEPTED'")
    long countReservedCitySlots(@Param("playerId") Long playerId);
}
