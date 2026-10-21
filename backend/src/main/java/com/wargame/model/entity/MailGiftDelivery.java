package com.wargame.model.entity;
import jakarta.persistence.*;
import lombok.Data;

/** 邮件删除后仍保留发放凭据，防止重复登录重发。 */
@Data @Entity
@Table(name = "mail_gift_deliveries", uniqueConstraints = @UniqueConstraint(columnNames = {"server_id", "activity_id", "player_id"}))
public class MailGiftDelivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "server_id", nullable = false, length = 40) private String serverId;
    @Column(name = "activity_id", nullable = false, length = 100) private String activityId;
    @Column(name = "player_id", nullable = false) private Long playerId;
    private Long mailId;
    @Column(nullable = false) private long issuedAt;
}
