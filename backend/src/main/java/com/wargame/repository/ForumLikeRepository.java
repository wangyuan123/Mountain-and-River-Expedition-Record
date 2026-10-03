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
}
