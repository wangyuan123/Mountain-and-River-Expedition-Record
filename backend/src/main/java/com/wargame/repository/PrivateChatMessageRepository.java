package com.wargame.repository;

import com.wargame.model.entity.PrivateChatMessage;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface PrivateChatMessageRepository extends JpaRepository<PrivateChatMessage, Long> {
    // 查询条件始终包含登录玩家，第三方无法通过指定双方 ID 读取私聊。
    @Query("select m from PrivateChatMessage m where (m.senderId = :me and m.recipientId = :peer) or (m.senderId = :peer and m.recipientId = :me) order by m.id desc")
    List<PrivateChatMessage> history(@Param("me") Long me, @Param("peer") Long peer, Pageable page);

    @Query("select m from PrivateChatMessage m where m.id in (select max(c.id) from PrivateChatMessage c where c.senderId = :me or c.recipientId = :me group by case when c.senderId = :me then c.recipientId else c.senderId end) order by m.id desc")
    List<PrivateChatMessage> conversations(@Param("me") Long me, Pageable page);

    long countBySenderIdAndRecipientIdAndReadByRecipientFalse(Long senderId, Long recipientId);

    @Modifying
    @Query("update PrivateChatMessage m set m.readByRecipient = true where m.senderId = :peer and m.recipientId = :me and m.id <= :through")
    void markRead(@Param("me") Long me, @Param("peer") Long peer, @Param("through") Long through);
}
