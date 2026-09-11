package com.wargame.repository;

import com.wargame.model.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByUsernameAndDisabled(String username, Integer disabled);

    List<Player> findByDisabledNotOrDisabledIsNull(Integer disabled);

    Optional<Player> findByCityPosXAndCityPosY(Integer cityPosX, Integer cityPosY);
}
