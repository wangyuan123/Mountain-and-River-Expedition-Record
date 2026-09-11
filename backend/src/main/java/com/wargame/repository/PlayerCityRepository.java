package com.wargame.repository;

import com.wargame.model.entity.PlayerCity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlayerCityRepository extends JpaRepository<PlayerCity, Long> {

    List<PlayerCity> findByWorldId(Long worldId);

    List<PlayerCity> findByOwnerId(Long ownerId);
}
