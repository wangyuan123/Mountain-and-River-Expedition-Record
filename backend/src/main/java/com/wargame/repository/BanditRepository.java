package com.wargame.repository;

import com.wargame.model.entity.Bandit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BanditRepository extends JpaRepository<Bandit, Long> {

    List<Bandit> findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(Long worldId, int minX, int maxX, int minY, int maxY);

    List<Bandit> findByWorldId(Long worldId);

    List<Bandit> findByWorldIdAndDefeatedFalse(Long worldId);
}
