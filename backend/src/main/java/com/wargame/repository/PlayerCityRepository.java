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
    List<PlayerCity> findByOwnerIdInAndCitySlotIsNotNull(java.util.Collection<Long> ownerIds);
    List<PlayerCity> findByRadarEnabledTrueAndCitySlotIsNotNull();
    java.util.Optional<PlayerCity> findByOwnerIdAndCitySlot(Long ownerId, Integer citySlot);
    List<PlayerCity> findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(Long ownerId);

    /** 账号锁内使用当前读，避免 MySQL 可重复读快照遗漏刚提交的城市及槽位。 */
    @org.springframework.data.jpa.repository.Query(value = "select city_slot from player_cities where owner_id = :ownerId "
            + "and city_slot is not null order by city_slot for update", nativeQuery = true)
    List<Integer> findCurrentSlotsForUpdate(@org.springframework.data.repository.query.Param("ownerId") Long ownerId);
    boolean existsByWorldIdAndXAndY(Long worldId, Integer x, Integer y);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from PlayerCity c where c.id = :id")
    java.util.Optional<PlayerCity> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
