package com.wargame.repository;

import com.wargame.model.entity.Mail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MailRepository extends JpaRepository<Mail, Long> {

    /** 收件箱：to_player_id = 当前玩家, 按时间倒序 */
    List<Mail> findByToPlayerIdOrderByCreatedAtDesc(Long toPlayerId);

    /** 发件箱：from_player_id = 当前玩家, 按时间倒序 */
    List<Mail> findByFromPlayerIdOrderByCreatedAtDesc(Long fromPlayerId);

    /** 未读数量 */
    long countByToPlayerIdAndIsReadFalse(Long toPlayerId);

    /** 收件人 ID 等于某玩家且邮件类型为 system (用于"系统"文件夹) */
    List<Mail> findByToPlayerIdAndIsSystemTrueOrderByCreatedAtDesc(Long toPlayerId);

    /** 标记已读 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Mail m SET m.isRead = true WHERE m.id = :id AND m.toPlayerId = :playerId")
    int markRead(@Param("id") Long id, @Param("playerId") Long playerId);

    /** 标记附件已领取 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Mail m SET m.isClaimed = true WHERE m.id = :id AND m.toPlayerId = :playerId")
    int markClaimed(@Param("id") Long id, @Param("playerId") Long playerId);

    /** 删除一封 (仅收件人能删) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Mail m WHERE m.id = :id AND m.toPlayerId = :playerId")
    int deleteByIdAndOwner(@Param("id") Long id, @Param("playerId") Long playerId);

    /** 删除某个玩家作为收件人的所有邮件（注销账号时清理） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Mail m WHERE m.toPlayerId = :playerId")
    int deleteByToPlayerId(@Param("playerId") Long playerId);

    /** 删除某个玩家作为发件人的所有邮件（注销账号时清理） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Mail m WHERE m.fromPlayerId = :playerId")
    int deleteByFromPlayerId(@Param("playerId") Long playerId);
}
