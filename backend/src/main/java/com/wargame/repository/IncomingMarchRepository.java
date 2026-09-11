package com.wargame.repository;

import com.wargame.model.entity.IncomingMarch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncomingMarchRepository extends JpaRepository<IncomingMarch, Long> {

    List<IncomingMarch> findByTargetPlayerId(Long targetPlayerId);

    void deleteByTargetPlayerId(Long targetPlayerId);
}
