package com.wargame.repository;

import com.wargame.model.entity.Resources;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResourcesRepository extends JpaRepository<Resources, Long> {

    Optional<Resources> findByPlayerId(Long playerId);

    void deleteByPlayerId(Long playerId);
}
