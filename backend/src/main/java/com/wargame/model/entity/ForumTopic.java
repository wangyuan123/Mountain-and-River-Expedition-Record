package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "forum_topics")
public class ForumTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "board_id", nullable = false)
    private Long boardId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "author_name", nullable = false, length = 50)
    private String authorName;

    @Column(name = "author_display_name", length = 100)
    private String authorDisplayName;

    @Column(name = "author_avatar", length = 255)
    private String authorAvatar;

    @Column(name = "author_military_rank", nullable = false)
    private Integer authorMilitaryRank = 1;

    @Column(name = "author_prestige", nullable = false)
    private Integer authorPrestige = 0;

    @Column(name = "author_guild_name", length = 50)
    private String authorGuildName;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "content", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    /** NORMAL, BOUNTY, BATTLE, ANNOUNCEMENT */
    @Column(name = "topic_type", nullable = false, length = 20)
    private String topicType = "NORMAL";

    @Column(name = "bounty_gold", nullable = false)
    private Integer bountyGold = 0;

    /** NONE, OPEN, SOLVED */
    @Column(name = "bounty_status", nullable = false, length = 20)
    private String bountyStatus = "NONE";

    @Column(name = "bounty_accepted_reply_id")
    private Long bountyAcceptedReplyId;

    @Column(name = "battle_report_id")
    private Long battleReportId;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;

    @Column(name = "reply_count", nullable = false)
    private Integer replyCount = 0;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Column(name = "tip_count", nullable = false)
    private Integer tipCount = 0;

    @Column(name = "tip_total_gold", nullable = false)
    private Integer tipTotalGold = 0;

    @Column(name = "is_pinned", nullable = false)
    private Boolean isPinned = false;

    @Column(name = "is_essence", nullable = false)
    private Boolean isEssence = false;

    @Column(name = "is_locked", nullable = false)
    private Boolean isLocked = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
