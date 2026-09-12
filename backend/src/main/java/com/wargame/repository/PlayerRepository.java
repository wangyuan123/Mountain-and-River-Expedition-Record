package com.wargame.repository;

import com.wargame.model.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerRepository extends JpaRepository<Player, Long> {

    @org.springframework.data.jpa.repository.Query("select p.id from Player p where p.id > :afterId "
            + "and (p.lastTick is null or p.lastTick <= :cutoff) order by p.id")
    List<Long> findDuePlayerIds(@org.springframework.data.repository.query.Param("afterId") long afterId,
                             @org.springframework.data.repository.query.Param("cutoff") long cutoff,
                             org.springframework.data.domain.Pageable page);

    List<Player> findByCityPosXBetweenAndCityPosYBetween(int minX, int maxX, int minY, int maxY);

    Optional<Player> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByUsernameAndDisabled(String username, Integer disabled);

    List<Player> findByDisabledNotOrDisabledIsNull(Integer disabled);

    Optional<Player> findByCityPosXAndCityPosY(Integer cityPosX, Integer cityPosY);
}
