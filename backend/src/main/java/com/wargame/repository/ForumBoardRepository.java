package com.wargame.repository;

import com.wargame.model.entity.ForumBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ForumBoardRepository extends JpaRepository<ForumBoard, Long> {
    /** 不同帖子同时发布或回复时，板块统计也须按行加锁。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b from ForumBoard b where b.id = :id")
    Optional<ForumBoard> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    List<ForumBoard> findAllByOrderBySortOrderAsc();
    Optional<ForumBoard> findByBoardKey(String boardKey);
}
