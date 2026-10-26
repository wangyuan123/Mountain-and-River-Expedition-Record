package com.wargame.repository;

import com.wargame.model.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerRepository extends JpaRepository<Player, Long>, JpaSpecificationExecutor<Player> {

    @org.springframework.data.jpa.repository.Query("select p.id from Player p where p.id > :afterId "
            + "and p.gameInitialized = true and p.accountStatus <> 'DELETED' and (p.accountStatus <> 'PENDING_DELETION' or p.recoverUntil > :cutoff) "
            + "and (p.lastTick is null or p.lastTick <= :cutoff) order by p.id")
    List<Long> findDuePlayerIds(@org.springframework.data.repository.query.Param("afterId") long afterId,
                             @org.springframework.data.repository.query.Param("cutoff") long cutoff,
                             org.springframework.data.domain.Pageable page);

    @org.springframework.data.jpa.repository.Query("select p.id from Player p where p.id > :afterId "
            + "and p.accountStatus = 'PENDING_DELETION' and p.recoverUntil <= :now order by p.id")
    List<Long> findExpiredAccountIds(@org.springframework.data.repository.query.Param("afterId") long afterId,
                                    @org.springframework.data.repository.query.Param("now") long now,
                                    org.springframework.data.domain.Pageable page);

    List<Player> findByCityPosXBetweenAndCityPosYBetween(int minX, int maxX, int minY, int maxY);

    Optional<Player> findByUsername(String username);

    Optional<Player> findByDisplayNameKey(String displayNameKey);

    boolean existsByUsername(String username);

    boolean existsByDisplayName(String displayName);

    boolean existsByDisplayNameKeyAndIdNot(String displayNameKey, Long id);

    @org.springframework.data.jpa.repository.Query("select count(p) from Player p where "
            + "(p.displayName is not null and lower(trim(p.displayName)) = lower(trim(:name))) "
            + "or lower(trim(p.username)) = lower(trim(:name))")
    long countByNameOrUsernameIgnoreCase(@org.springframework.data.repository.query.Param("name") String name);

    default boolean existsByNameOrUsername(String name) {
        if (name == null || name.isBlank()) return false;
        return countByNameOrUsernameIgnoreCase(name.trim()) > 0;
    }

    default String generateDefaultCommanderName() {
        return generateDefaultCommanderName("远征统帅", java.util.Collections.emptySet());
    }

    default String generateDefaultCommanderName(String prefix, java.util.Set<String> excludedNames) {
        String basePrefix = (prefix != null && !prefix.isBlank() && !"Bot".equalsIgnoreCase(prefix.trim()))
                ? prefix.trim()
                : "远征统帅";
        String prefixTag = basePrefix.endsWith("_") ? basePrefix : basePrefix + "_";
        String suffix = String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
        String candidate = prefixTag + suffix;
        int attempt = 0;
        while ((excludedNames != null && (excludedNames.contains(candidate.toLowerCase()) || excludedNames.contains(candidate))
                || existsByNameOrUsername(candidate))
                && attempt < 100) {
            suffix = String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
            candidate = prefixTag + suffix;
            attempt++;
        }
        if ((excludedNames != null && (excludedNames.contains(candidate.toLowerCase()) || excludedNames.contains(candidate)))
                || existsByNameOrUsername(candidate)) {
            throw new IllegalStateException("无法生成唯一的统帅名，请稍后再试");
        }
        return candidate;
    }

    List<Player> findByUsernameInAndAccountStatus(java.util.Collection<String> usernames, String accountStatus);

    boolean existsByUsernameAndDisabled(String username, Integer disabled);

    List<Player> findByDisabledNotOrDisabledIsNull(Integer disabled);

    Optional<Player> findByCityPosXAndCityPosY(Integer cityPosX, Integer cityPosY);

    List<Player> findByIsBotTrue();

    boolean existsByWarAgainstId(Long warAgainstId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Player p where p.id = :id")
    Optional<Player> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
