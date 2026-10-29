package com.wargame.repository;

import com.wargame.model.entity.ForumLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ForumLikeRepository extends JpaRepository<ForumLike, Long> {
    Optional<ForumLike> findByTargetTypeAndTargetIdAndPlayerId(String targetType, Long targetId, Long playerId);
    boolean existsByTargetTypeAndTargetIdAndPlayerId(String targetType, Long targetId, Long playerId);
    int countByTargetTypeAndTargetId(String targetType, Long targetId);

    /** 在回复删除前清理主题及其回复点赞，避免留下无法访问的互动记录。 */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from ForumLike l where (l.targetType = 'TOPIC' and l.targetId = :topicId) or (l.targetType = 'REPLY' and l.targetId in (select r.id from ForumReply r where r.topicId = :topicId))")
    void deleteForTopic(@org.springframework.data.repository.query.Param("topicId") Long topicId);
}
