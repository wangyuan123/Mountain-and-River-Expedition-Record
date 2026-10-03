package com.wargame.repository;

import com.wargame.model.entity.ForumReply;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ForumReplyRepository extends JpaRepository<ForumReply, Long> {

    Page<ForumReply> findByTopicIdOrderByFloorNumberAsc(Long topicId, Pageable pageable);

    List<ForumReply> findByTopicIdOrderByFloorNumberAsc(Long topicId);

    int countByTopicId(Long topicId);

    Page<ForumReply> findByAuthorIdOrderByCreatedAtDesc(Long authorId, Pageable pageable);
}
