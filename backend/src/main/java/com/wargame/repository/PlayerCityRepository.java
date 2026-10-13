package com.wargame.repository;

import com.wargame.model.entity.PlayerCity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlayerCityRepository extends JpaRepository<PlayerCity, Long> {

    List<PlayerCity> findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(Long worldId, int minX, int maxX, int minY, int maxY);

    List<PlayerCity> findByWorldId(Long worldId);

    List<PlayerCity> findByOwnerId(Long ownerId);
    java.util.Optional<PlayerCity> findByOwnerIdAndCitySlot(Long ownerId, Integer citySlot);
    List<PlayerCity> findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(Long ownerId);
    boolean existsByWorldIdAndXAndY(Long worldId, Integer x, Integer y);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from PlayerCity c where c.id = :id")
    java.util.Optional<PlayerCity> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
