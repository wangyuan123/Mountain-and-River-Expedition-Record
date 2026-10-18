package com.wargame.repository;

import com.wargame.model.entity.FriendContact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface FriendContactRepository extends JpaRepository<FriendContact, Long> {
    Optional<FriendContact> findByOwnerIdAndPeerId(Long ownerId, Long peerId);
    List<FriendContact> findByOwnerIdOrderByIdAsc(Long ownerId);
    List<FriendContact> findByOwnerIdAndGroupId(Long ownerId, Long groupId);
    boolean existsByOwnerIdAndPeerIdAndBlockedTrue(Long ownerId, Long peerId);

    /** 任一方拉黑都会阻止双方继续发送，所有私聊入口共用此约束。 */
    default boolean blocksChat(Long first, Long second) {
        return existsByOwnerIdAndPeerIdAndBlockedTrue(first, second)
                || existsByOwnerIdAndPeerIdAndBlockedTrue(second, first);
    }
}
