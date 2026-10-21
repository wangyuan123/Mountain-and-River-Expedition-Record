package com.wargame.repository;

import com.wargame.model.entity.PrivateChatMessage;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface PrivateChatMessageRepository extends JpaRepository<PrivateChatMessage, Long> {
    default List<PrivateChatMessage> history(Long me, Long peer, Pageable page) {
        return historyVisible(me, peer, 0L, System.currentTimeMillis(), page);
    }
    default List<PrivateChatMessage> conversations(Long me, Pageable page) {
        return conversationsVisible(me, System.currentTimeMillis(), page);
    }
    default long countBySenderIdAndRecipientIdAndReadByRecipientFalse(Long sender, Long recipient) {
        return countUnreadVisible(sender, recipient, System.currentTimeMillis(), 0L);
    }
    default void markRead(Long me, Long peer, Long through) {
        markRead(me, peer, through, 0L, System.currentTimeMillis());
    }
    // 查询条件始终包含登录玩家，第三方无法通过指定双方 ID 读取私聊。
    @Query("select m from PrivateChatMessage m where (((m.senderId = :me and m.recipientId = :peer) or (m.senderId = :peer and m.recipientId = :me)) " +
            "and m.retentionExpiresAt > :now and m.id > :clearedThrough) order by m.id desc")
    List<PrivateChatMessage> historyVisible(@Param("me") Long me, @Param("peer") Long peer,
                                            @Param("clearedThrough") Long clearedThrough, @Param("now") long now, Pageable page);

    @Query(value = "select m.* from private_chat_messages m where (m.sender_id = :me or m.recipient_id = :me) " +
            "and m.retention_expires_at > :now and not exists (select 1 from private_chat_messages newer " +
            "where newer.retention_expires_at > :now and newer.id > m.id and " +
            "((newer.sender_id = :me and newer.recipient_id = case when m.sender_id = :me then m.recipient_id else m.sender_id end) or " +
            "(newer.recipient_id = :me and newer.sender_id = case when m.sender_id = :me then m.recipient_id else m.sender_id end))) " +
            "and not exists (select 1 from private_chat_conversations c where c.owner_id = :me " +
            "and c.peer_id = case when m.sender_id = :me then m.recipient_id else m.sender_id end " +
            "and m.id <= c.deleted_through) order by m.id desc", nativeQuery = true)
    List<PrivateChatMessage> conversationsVisible(@Param("me") Long me, @Param("now") long now, Pageable page);

    @Query("select count(m) from PrivateChatMessage m where m.senderId = :sender and m.recipientId = :recipient " +
            "and m.readByRecipient = false and m.retentionExpiresAt > :now and m.id > :clearedThrough")
    long countUnreadVisible(@Param("sender") Long sender, @Param("recipient") Long recipient,
                            @Param("now") long now, @Param("clearedThrough") Long clearedThrough);

    @Modifying
    @Query("update PrivateChatMessage m set m.readByRecipient = true where m.senderId = :peer and m.recipientId = :me " +
            "and m.id <= :through and m.id > :clearedThrough and m.retentionExpiresAt > :now")
    void markRead(@Param("me") Long me, @Param("peer") Long peer, @Param("through") Long through,
                  @Param("clearedThrough") Long clearedThrough, @Param("now") long now);
}
