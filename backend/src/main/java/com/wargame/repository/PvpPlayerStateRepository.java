package com.wargame.repository;
import com.wargame.model.entity.PvpPlayerState;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface PvpPlayerStateRepository extends JpaRepository<PvpPlayerState, Long> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PvpPlayerState s where s.playerId = :id")
    Optional<PvpPlayerState> lock(@Param("id") Long id);
}
