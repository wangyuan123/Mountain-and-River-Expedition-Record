package com.wargame.repository;

import com.wargame.model.entity.GuildChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GuildChatMessageRepository extends JpaRepository<GuildChatMessage, Long> {
    List<GuildChatMessage> findTop50ByGuildIdOrderByCreatedAtDesc(Long guildId);
}
