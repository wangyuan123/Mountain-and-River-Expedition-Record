package com.wargame.model.entity;
import jakarta.persistence.*;
import lombok.Data;

/** 活动奖励快照一经发布不再修改，按真实时间窗口发放。 */
@Data @Entity @Table(name = "mail_gift_activities")
public class MailGiftActivity {
    @Id @Column(length = 100) private String id;
    @Column(nullable = false, length = 40) private String serverId;
    @Column(nullable = false) private long startsAt;
    @Column(nullable = false) private long endsAt;
    @Column(nullable = false) private long claimExpiresAt;
    @Column(nullable = false) private long noticeExpiresAt;
    @Column(nullable = false) private long cleanupAfter;
    @Column(nullable = false) private boolean closed;
    @Column(nullable = false) private boolean newAccountsOnly;
    @Column(nullable = false, length = 80) private String subject;
    @Column(nullable = false, columnDefinition = "TEXT") private String body;
    @Column(nullable = false, columnDefinition = "TEXT") private String rewardsJson;
}
