package com.wargame.repository;

import com.wargame.model.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findTop50ByOrderByCreatedAtDesc();

    @Query("select m from ChatMessage m where m.retentionExpiresAt > :now order by m.createdAt desc, m.id desc")
    List<ChatMessage> findRecentVisible(@Param("now") long now, org.springframework.data.domain.Pageable pageable);
}
