package com.wargame.repository;

import com.wargame.model.entity.WorldMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorldMapRepository extends JpaRepository<WorldMap, Long> {

    Optional<WorldMap> findFirstByOrderByIdAsc();

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from WorldMap w where w.id = :id")
    Optional<WorldMap> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
