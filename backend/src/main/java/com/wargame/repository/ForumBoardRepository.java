package com.wargame.repository;

import com.wargame.model.entity.ForumBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ForumBoardRepository extends JpaRepository<ForumBoard, Long> {
    List<ForumBoard> findAllByOrderBySortOrderAsc();
    Optional<ForumBoard> findByBoardKey(String boardKey);
}
