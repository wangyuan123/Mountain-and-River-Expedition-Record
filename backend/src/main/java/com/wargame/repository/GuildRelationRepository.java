package com.wargame.repository;

import com.wargame.model.entity.GuildRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GuildRelationRepository extends JpaRepository<GuildRelation, Long> {
    Optional<GuildRelation> findByGuildLowIdAndGuildHighId(Long guildLowId, Long guildHighId);
    List<GuildRelation> findByGuildLowIdOrGuildHighIdOrderByUpdatedAtDesc(Long guildLowId, Long guildHighId);
}
