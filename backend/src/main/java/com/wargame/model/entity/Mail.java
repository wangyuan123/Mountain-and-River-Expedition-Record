package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 邮件实体。
 * <p>
 * 一行代表一封邮件，方向由 from_player_id / to_player_id 决定。
 * 玩家间互发时两边都各有一行？不，只有一行：from=A, to=B，B 在收件箱看到。
 * A 想看自己发出去的，发件箱通过 from_player_id=A 反查即可。
 * <p>
 * 系统邮件 from_player_id = NULL，但 from_name 仍然有展示名（"系统"/"战报中心" 等）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "mails")
public class Mail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发件人玩家 ID；NULL 表示系统发件 */
    @Column(name = "from_player_id")
    private Long fromPlayerId;

    /** 发件人显示名（玩家 username 或 "系统"/"战报中心"） */
    @Column(name = "from_name", nullable = false, length = 50)
    private String fromName;

    @Column(name = "to_player_id", nullable = false)
    private Long toPlayerId;

    @Column(name = "to_name", nullable = false, length = 50)
    private String toName;

    /** system / reward / alliance / combat / player */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = false;

    @Column(name = "subject", nullable = false, length = 80)
    private String subject;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** 附件 JSON: [{"type":"gold","qty":200}] */
    @Column(name = "attach_json", columnDefinition = "TEXT")
    private String attachJson;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "is_claimed", nullable = false)
    private Boolean isClaimed = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
