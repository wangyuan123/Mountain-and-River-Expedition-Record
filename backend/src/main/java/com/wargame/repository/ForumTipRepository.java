package com.wargame.repository;

import com.wargame.model.entity.ForumTip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ForumTipRepository extends JpaRepository<ForumTip, Long> {
    List<ForumTip> findByTopicIdOrderByCreatedAtDesc(Long topicId);
    List<ForumTip> findTop20ByTopicIdOrderByCreatedAtDesc(Long topicId);
}
