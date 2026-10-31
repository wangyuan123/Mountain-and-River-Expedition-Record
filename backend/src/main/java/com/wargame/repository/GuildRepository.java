package com.wargame.repository;

import com.wargame.model.entity.Guild;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GuildRepository extends JpaRepository<Guild, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select g from Guild g where g.id = :id")
    Optional<Guild> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Guild> findByName(String name);
    boolean existsByName(String name);
}
