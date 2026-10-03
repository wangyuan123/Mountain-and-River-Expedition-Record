package com.wargame.repository;

import com.wargame.model.entity.ForumTopic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ForumTopicRepository extends JpaRepository<ForumTopic, Long> {

    @Query("SELECT t FROM ForumTopic t WHERE (:boardId IS NULL OR t.boardId = :boardId) " +
           "AND (:isEssence IS NULL OR t.isEssence = :isEssence) " +
           "AND (:topicType IS NULL OR t.topicType = :topicType) " +
           "AND (:query IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "     OR LOWER(t.authorName) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY t.isPinned DESC, t.updatedAt DESC")
    Page<ForumTopic> findTopics(
            @Param("boardId") Long boardId,
            @Param("isEssence") Boolean isEssence,
            @Param("topicType") String topicType,
            @Param("query") String query,
            Pageable pageable
    );

    List<ForumTopic> findTop10ByOrderByIsPinnedDescViewCountDesc();

    Page<ForumTopic> findByAuthorIdOrderByCreatedAtDesc(Long authorId, Pageable pageable);

    long countByBoardId(Long boardId);
}
