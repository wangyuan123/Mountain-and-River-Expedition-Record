package com.wargame.repository;

import com.wargame.model.entity.FriendGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface FriendGroupRepository extends JpaRepository<FriendGroup, Long> {
    List<FriendGroup> findByOwnerIdOrderByIdAsc(Long ownerId);
    Optional<FriendGroup> findByIdAndOwnerId(Long id, Long ownerId);
}
