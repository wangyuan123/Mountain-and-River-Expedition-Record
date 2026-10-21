package com.wargame.repository;

import com.wargame.model.entity.GuildChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Repository
public interface GuildChatMessageRepository extends JpaRepository<GuildChatMessage, Long> {
    List<GuildChatMessage> findTop50ByGuildIdOrderByCreatedAtDesc(Long guildId);
    @Query("select m from GuildChatMessage m where m.guildId = :guildId and m.retentionExpiresAt > :now order by m.createdAt desc, m.id desc")
    List<GuildChatMessage> findRecentVisible(@Param("guildId") Long guildId, @Param("now") long now, Pageable pageable);
}
