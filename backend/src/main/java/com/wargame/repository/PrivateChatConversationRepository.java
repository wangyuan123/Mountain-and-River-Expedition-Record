package com.wargame.repository;

import com.wargame.model.entity.PrivateChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PrivateChatConversationRepository extends JpaRepository<PrivateChatConversation, Long> {
    Optional<PrivateChatConversation> findByOwnerIdAndPeerId(Long ownerId, Long peerId);
    List<PrivateChatConversation> findTop50ByOwnerIdOrderByOpenedAtDesc(Long ownerId);
    List<PrivateChatConversation> findByOwnerId(Long ownerId);
}
