package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "forum_replies")
public class ForumReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic_id", nullable = false)
    private Long topicId;

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

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "floor_number", nullable = false)
    private Integer floorNumber = 1;

    @Column(name = "quote_reply_id")
    private Long quoteReplyId;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Column(name = "is_accepted_bounty", nullable = false)
    private Boolean isAcceptedBounty = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
