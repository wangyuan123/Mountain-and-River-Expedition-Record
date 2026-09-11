package com.wargame.repository;

import com.wargame.model.entity.GuildApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GuildApplicationRepository extends JpaRepository<GuildApplication, Long> {
    Optional<GuildApplication> findByGuildIdAndPlayerId(Long guildId, Long playerId);
    List<GuildApplication> findByGuildIdOrderByCreatedAtAsc(Long guildId);
    void deleteByGuildIdAndPlayerId(Long guildId, Long playerId);
}
