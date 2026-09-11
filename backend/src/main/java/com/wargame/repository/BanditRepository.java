package com.wargame.repository;

import com.wargame.model.entity.Bandit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BanditRepository extends JpaRepository<Bandit, Long> {

    List<Bandit> findByWorldId(Long worldId);

    List<Bandit> findByWorldIdAndDefeatedFalse(Long worldId);
}
