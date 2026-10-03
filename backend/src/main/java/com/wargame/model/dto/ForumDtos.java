package com.wargame.model.dto;

import java.util.List;

public final class ForumDtos {

    private ForumDtos() {}

    public record CreateTopicRequest(
            Long boardId,
            String title,
            String content,
            String topicType,
            Integer bountyGold,
            Long battleReportId
    ) {}

    public record CreateReplyRequest(
            String content,
            Long quoteReplyId
    ) {}

    public record TipRequest(
            Integer amountGold,
            String message
    ) {}

    public record AdminTopicActionRequest(
            String action // PIN, UNPIN, ESSENCE, UNESSENCE, LOCK, UNLOCK, DELETE
    ) {}

    public record ForumProfileDto(
            Long id,
            String username,
            String displayName,
            String avatar,
            Integer militaryRank,
            String militaryRankName,
            Integer prestige,
            Long guildId,
            String guildName,
            String guildRole,
            Integer gold,
            Integer cityPosX,
            Integer cityPosY,
            boolean canPost,
            boolean canReply,
            boolean isAdmin
    ) {}

    public record TopicSummaryDto(
            Long id,
            Long boardId,
            String boardName,
            String boardKey,
            Long authorId,
            String authorName,
            String authorDisplayName,
            String authorAvatar,
            Integer authorMilitaryRank,
            String authorMilitaryRankName,
            Integer authorPrestige,
            String authorGuildName,
            String title,
            String topicType,
            Integer bountyGold,
            String bountyStatus,
            Long battleReportId,
            Integer viewCount,
            Integer replyCount,
            Integer likeCount,
            Integer tipCount,
            Integer tipTotalGold,
            Boolean isPinned,
            Boolean isEssence,
            Boolean isLocked,
            Long createdAt,
            Long updatedAt
    ) {}

    public record TopicDetailDto(
            TopicSummaryDto topic,
            String content,
            boolean isLikedByMe,
            Object battleReport,
            List<TipDto> recentTips
    ) {}

    public record ReplyDto(
            Long id,
            Long topicId,
            Long authorId,
            String authorName,
            String authorDisplayName,
            String authorAvatar,
            Integer authorMilitaryRank,
            String authorMilitaryRankName,
            Integer authorPrestige,
            String authorGuildName,
            String content,
            Integer floorNumber,
            QuoteReplyDto quoteReply,
            Integer likeCount,
            boolean isLikedByMe,
            Boolean isAcceptedBounty,
            Long createdAt
    ) {}

    public record QuoteReplyDto(
            Long id,
            String authorName,
            Integer floorNumber,
            String snippet
    ) {}

    public record TipDto(
            Long id,
            Long senderId,
            String senderName,
            Integer amountGold,
            String message,
            Long createdAt
    ) {}
}
