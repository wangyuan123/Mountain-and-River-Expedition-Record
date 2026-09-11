package com.wargame.repository;

import com.wargame.model.entity.GuildMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GuildMemberRepository extends JpaRepository<GuildMember, Long> {
    Optional<GuildMember> findByPlayerId(Long playerId);
    List<GuildMember> findByGuildIdOrderByJoinedAtAsc(Long guildId);
    long countByGuildId(Long guildId);
    void deleteByPlayerId(Long playerId);
}
