package com.wargame.repository;

import com.wargame.model.entity.FriendRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {
    Optional<FriendRequest> findBySenderIdAndRecipientId(Long senderId, Long recipientId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from FriendRequest r where r.id = :id")
    Optional<FriendRequest> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);
    List<FriendRequest> findBySenderIdOrRecipientIdOrderByIdDesc(Long senderId, Long recipientId);
}
