package com.wargame.repository;

import com.wargame.model.entity.PlayerItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerItemRepository extends JpaRepository<PlayerItem, Long> {

    List<PlayerItem> findByPlayerId(Long playerId);

    Optional<PlayerItem> findByPlayerIdAndItemKey(Long playerId, String itemKey);

    void deleteByPlayerId(Long playerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PlayerItem p SET p.count = p.count - :delta, p.updatedAt = :now " +
           "WHERE p.playerId = :playerId AND p.itemKey = :itemKey AND p.count >= :delta")
    int tryConsume(@Param("playerId") Long playerId,
                   @Param("itemKey") String itemKey,
                   @Param("delta") int delta,
                   @Param("now") long now);
}
