package com.wargame.service;

import com.wargame.model.UserPrincipal;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.dto.ForumDtos.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.security.RateLimiter;
import com.wargame.util.JsonUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ForumService {
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TickService resourceEconomy;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.config.RetentionPolicy retentionPolicy;
    @org.springframework.beans.factory.annotation.Autowired private ReportPrivacyService reportPrivacy;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.quest.QuestService questService;

    private final ForumBoardRepository boardRepository;
    private final ForumTopicRepository topicRepository;
    private final ForumReplyRepository replyRepository;
    private final ForumLikeRepository likeRepository;
    private final ForumTipRepository tipRepository;
    private final PlayerRepository playerRepository;
    private final ResourcesRepository resourcesRepository;
    private final GuildMemberRepository guildMemberRepository;
    private final GuildRepository guildRepository;
    private final ScoutReportRepository scoutReportRepository;
    private final RateLimiter rateLimiter;

    public ForumService(ForumBoardRepository boardRepository,
                        ForumTopicRepository topicRepository,
                        ForumReplyRepository replyRepository,
                        ForumLikeRepository likeRepository,
                        ForumTipRepository tipRepository,
                        PlayerRepository playerRepository,
                        ResourcesRepository resourcesRepository,
                        GuildMemberRepository guildMemberRepository,
                        GuildRepository guildRepository,
                        ScoutReportRepository scoutReportRepository,
                        RateLimiter rateLimiter) {
        this.boardRepository = boardRepository;
        this.topicRepository = topicRepository;
        this.replyRepository = replyRepository;
        this.likeRepository = likeRepository;
        this.tipRepository = tipRepository;
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.guildMemberRepository = guildMemberRepository;
        this.guildRepository = guildRepository;
        this.scoutReportRepository = scoutReportRepository;
        this.rateLimiter = rateLimiter;
    }

    @jakarta.annotation.PostConstruct
    public void initDefaultBoards() {
        if (boardRepository.count() == 0) {
            boardRepository.saveAll(List.of(
                    new ForumBoard(null, "strategy", "参谋本部", "作战指导、配兵克制、将领培养与野地攻坚心得", "Compass", 0, 1, 0, 0),
                    new ForumBoard(null, "tavern", "战地茶馆", "前线见闻、军友闲谈、同盟吹水交流区", "Coffee", 0, 2, 0, 0),
                    new ForumBoard(null, "legion", "军团动员处", "远征军各军团招募联络、阵营联合檄文与公约", "Flag", 0, 3, 0, 0),
                    new ForumBoard(null, "battle_hall", "战功英雄榜", "经典大捷战报复盘、史诗攻防战观摩与讨论", "Trophy", 0, 4, 0, 0),
                    new ForumBoard(null, "notice", "后勤联络部", "指挥部更新公告、战备Bug反馈与参谋提议", "Bell", 0, 5, 0, 0)
            ));
        }
    }

    public List<ForumBoard> getBoards() {
        return boardRepository.findAllByOrderBySortOrderAsc();
    }

    public Page<TopicSummaryDto> getTopics(Long boardId, Boolean isEssence, String topicType, String query, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        String cleanQuery = (query != null && !query.trim().isEmpty()) ? query.trim() : null;
        String cleanType = (topicType != null && !topicType.trim().isEmpty() && !topicType.equalsIgnoreCase("ALL")) ? topicType.trim().toUpperCase() : null;
        Page<ForumTopic> topics = topicRepository.findTopics(boardId, isEssence, cleanType, cleanQuery, pageable);
        Map<Long, VipIdentity> authors = getAuthorVipIdentities(topics.getContent().stream().map(ForumTopic::getAuthorId).toList());
        return topics.map(t -> toSummaryDto(t, authors.getOrDefault(t.getAuthorId(), NO_VIP)));
    }

    public List<TopicSummaryDto> getHotTopics() {
        List<ForumTopic> topics = topicRepository.findTop10ByOrderByIsPinnedDescViewCountDesc();
        Map<Long, VipIdentity> authors = getAuthorVipIdentities(topics.stream().map(ForumTopic::getAuthorId).toList());
        return topics.stream().map(t -> toSummaryDto(t, authors.getOrDefault(t.getAuthorId(), NO_VIP))).toList();
    }

    @Transactional
    public TopicDetailDto getTopicDetail(Long topicId) {
        ForumTopic topic = topicRepository.lockById(topicId)
                .orElseThrow(() -> new IllegalArgumentException("话题不存在或已被删除"));

        topic.setViewCount(topic.getViewCount() + 1);
        topicRepository.save(topic);

        Long currentOptPlayerId = getOptionalCurrentPlayerId().orElse(null);
        boolean isLikedByMe = false;
        if (currentOptPlayerId != null) {
            isLikedByMe = likeRepository.existsByTargetTypeAndTargetIdAndPlayerId("TOPIC", topicId, currentOptPlayerId);
        }

        Object battleReportData = null;
        if (topic.getBattleReportId() != null) {
            ScoutReport sr = scoutReportRepository.findById(topic.getBattleReportId()).orElse(null);
            long realNow = System.currentTimeMillis();
            if (sr != null && sr.getPlayerId().equals(topic.getAuthorId()) && sr.getData() != null && !sr.getData().isBlank()
                    && topic.getBattleReportExpiresAt() != null && topic.getBattleReportExpiresAt() > realNow
                    && (sr.getStarredAt() != null || sr.getDetailExpiresAt() != null && sr.getDetailExpiresAt() > realNow)) {
                try {
                    Map<String, Object> snapshot = JsonUtil.parseObjMap(sr.getData());
                    // 论坛嵌入与聊天分享使用同一脱敏和真实到期边界。
                    reportPrivacy.anonymize(List.of(snapshot));
                    snapshot.put("expiresAt", sr.getStarredAt() != null ? topic.getBattleReportExpiresAt()
                            : Math.min(topic.getBattleReportExpiresAt(), sr.getDetailExpiresAt()));
                    battleReportData = snapshot;
                } catch (Exception ignored) {}
            } else battleReportData = Map.of("detailsAvailable", false, "expired", true, "message", "分享战报已过期");
        }

        List<TipDto> recentTips = tipRepository.findTop20ByTopicIdOrderByCreatedAtDesc(topicId)
                .stream().map(t -> new TipDto(
                        t.getId(),
                        t.getSenderId(),
                        t.getSenderName(),
                        t.getAmountGold(),
                        t.getMessage(),
                        t.getCreatedAt()
                )).toList();

        return new TopicDetailDto(
                toSummaryDto(topic),
                topic.getContent(),
                isLikedByMe,
                battleReportData,
                recentTips
        );
    }

    @Transactional
    public TopicSummaryDto createTopic(Long playerId, CreateTopicRequest req) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        if (req.title() == null || req.title().trim().isEmpty() || req.title().length() > 120) {
            throw new IllegalArgumentException("标题不能为空且长度不能超过120字");
        }
        if (req.content() == null || req.content().trim().isEmpty() || req.content().length() > 20000) {
            throw new IllegalArgumentException("帖子内容不能为空且长度不能超过20000字");
        }

        if (req.boardId() == null) throw new IllegalArgumentException("请选择发布板块");
        ForumBoard board = boardRepository.lockById(req.boardId())
                .orElseThrow(() -> new IllegalArgumentException("指定板块不存在"));

        int playerPrestige = player.getPrestige() != null ? player.getPrestige() : 0;
        int minReq = Math.max(50, board.getMinPrestige());
        if (playerPrestige < minReq) {
            throw new IllegalArgumentException("您的声望不足 " + minReq + "，暂无法在此板块发帖");
        }

        String type = (req.topicType() != null && !req.topicType().isBlank()) ? req.topicType().toUpperCase() : "NORMAL";
        if (!Set.of("NORMAL", "BOUNTY", "BATTLE").contains(type)) throw new IllegalArgumentException("文书性质无效");
        if ("BOUNTY".equals(type) && (req.bountyGold() == null || req.bountyGold() <= 0)) {
            throw new IllegalArgumentException("悬赏金额必须为正整数");
        }
        int bountyGold = 0;
        String bountyStatus = "NONE";

        if ("BOUNTY".equals(type)) {
            bountyGold = req.bountyGold();
            resourceEconomy.beforeResourceChange(playerId, 0);
            Resources res = resourcesRepository.findCityForTreatment(playerId, 0)
                    .orElseThrow(() -> new IllegalStateException("未找到玩家主城金库资源"));
            long currentGold = res.getGold() != null ? res.getGold() : 0L;
            if (currentGold < bountyGold) {
                throw new IllegalArgumentException("黄金储备不足，当前拥有 " + currentGold + "，需要 " + bountyGold + " 黄金用于悬赏");
            }
            res.setGold(currentGold - bountyGold);
            resourcesRepository.save(res);
            bountyStatus = "OPEN";
        }

        Long battleReportId = null;
        Long battleReportExpiresAt = null;
        if (req.battleReportId() != null) {
            ScoutReport sr = scoutReportRepository.findById(req.battleReportId()).orElse(null);
            long realNow = System.currentTimeMillis();
            if (sr == null || !playerId.equals(sr.getPlayerId()) || sr.getData() == null || (sr.getStarredAt() == null && (sr.getDetailExpiresAt() == null || sr.getDetailExpiresAt() <= realNow))) throw new IllegalArgumentException("战报不存在、无权分享或详情已过期");
            if (sr != null) {
                battleReportId = sr.getId();
                battleReportExpiresAt = sr.getStarredAt() != null ? realNow + retentionPolicy.days(retentionPolicy.getReportShareDays()) : Math.min(sr.getDetailExpiresAt(), realNow + retentionPolicy.days(retentionPolicy.getReportShareDays()));
                if (!"BOUNTY".equals(type)) {
                    type = "BATTLE";
                }
            }
        }

        // 无效表单不占用成功发帖的冷却；重复有效提交返回明确的 429。
        if (!rateLimiter.allow("FORUM_POST:" + playerId, 1, 15_000L)) {
            throw new com.wargame.security.GameAccessException("FORUM_POST_COOLDOWN", "发帖过于频繁，请稍候再试（冷却时间15秒）", 429);
        }
        long now = System.currentTimeMillis();
        GuildInfo guildInfo = getPlayerGuildInfo(playerId);

        ForumTopic topic = new ForumTopic();
        topic.setBoardId(board.getId());
        topic.setAuthorId(playerId);
        topic.setAuthorName(player.getUsername());
        topic.setAuthorDisplayName(player.getDisplayName());
        topic.setAuthorAvatar(player.getAvatar());
        topic.setAuthorMilitaryRank(player.getMilitaryRank() != null ? player.getMilitaryRank() : 1);
        topic.setAuthorPrestige(playerPrestige);
        topic.setAuthorGuildName(guildInfo.guildName());
        topic.setTitle(req.title().trim());
        topic.setContent(req.content().trim());
        topic.setTopicType(type);
        topic.setBountyGold(bountyGold);
        topic.setBountyStatus(bountyStatus);
        topic.setBattleReportId(battleReportId);
        topic.setBattleReportExpiresAt(battleReportExpiresAt);
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);

        topic = topicRepository.save(topic);

        board.setTopicCount(board.getTopicCount() + 1);
        boardRepository.save(board);

        try {
            if (questService != null) questService.onEvent(playerId, "FORUM_POST", null, 1);
        } catch (Exception ignored) {}

        return toSummaryDto(topic);
    }

    public Page<ReplyDto> getReplies(Long topicId, int page, int size) {
        if (!topicRepository.existsById(topicId)) throw new IllegalArgumentException("话题不存在或已被删除");
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        Long currentOptPlayerId = getOptionalCurrentPlayerId().orElse(null);

        Page<ForumReply> replies = replyRepository.findByTopicIdOrderByFloorNumberAsc(topicId, pageable);
        Map<Long, VipIdentity> authors = getAuthorVipIdentities(replies.getContent().stream().map(ForumReply::getAuthorId).toList());
        return replies.map(r -> {
                    VipIdentity vip = authors.getOrDefault(r.getAuthorId(), NO_VIP);
                    boolean isLiked = false;
                    if (currentOptPlayerId != null) {
                        isLiked = likeRepository.existsByTargetTypeAndTargetIdAndPlayerId("REPLY", r.getId(), currentOptPlayerId);
                    }
                    QuoteReplyDto quote = null;
                    if (r.getQuoteReplyId() != null) {
                        ForumReply quoted = replyRepository.findById(r.getQuoteReplyId()).orElse(null);
                        if (quoted != null && topicId.equals(quoted.getTopicId())) {
                            String snippet = quoted.getContent().length() > 60
                                    ? quoted.getContent().substring(0, 60) + "..." : quoted.getContent();
                            quote = new QuoteReplyDto(quoted.getId(), quoted.getAuthorDisplayName() != null ? quoted.getAuthorDisplayName() : quoted.getAuthorName(), quoted.getFloorNumber(), snippet);
                        }
                    }
                    return new ReplyDto(
                            r.getId(),
                            r.getTopicId(),
                            r.getAuthorId(),
                            r.getAuthorName(),
                            r.getAuthorDisplayName(),
                            r.getAuthorAvatar(),
                            vip.level(),
                            vip.visible(),
                            r.getAuthorMilitaryRank(),
                            MilitaryRankDef.getRankName(r.getAuthorMilitaryRank()),
                            r.getAuthorPrestige(),
                            r.getAuthorGuildName(),
                            r.getContent(),
                            r.getFloorNumber(),
                            quote,
                            r.getLikeCount(),
                            isLiked,
                            r.getIsAcceptedBounty(),
                            r.getCreatedAt()
                    );
                });
    }

    @Transactional
    public ReplyDto createReply(Long playerId, Long topicId, CreateReplyRequest req) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        if (req.content() == null || req.content().trim().isEmpty() || req.content().length() > 5000) {
            throw new IllegalArgumentException("回帖内容不能为空且长度不能超过5000字");
        }

        ForumTopic topic = topicRepository.lockById(topicId)
                .orElseThrow(() -> new IllegalArgumentException("话题不存在"));

        if (Boolean.TRUE.equals(topic.getIsLocked())) {
            throw new IllegalArgumentException("该话题已被锁定，暂不可继续回帖");
        }

        // 与 profile.canReply 共用 10 声望门槛；引用只允许指向当前仍存在的同帖回复。
        if (Objects.requireNonNullElse(player.getPrestige(), 0) < 10) throw new IllegalArgumentException("您的声望不足 10，暂无法回复");
        if (req.quoteReplyId() != null) {
            ForumReply quoted = replyRepository.findById(req.quoteReplyId())
                    .orElseThrow(() -> new IllegalArgumentException("引用的回复不存在"));
            if (!topicId.equals(quoted.getTopicId())) throw new IllegalArgumentException("不能引用其他话题的回复");
        }
        if (!rateLimiter.allow("FORUM_REPLY:" + playerId, 1, 8_000L)) {
            throw new com.wargame.security.GameAccessException("FORUM_REPLY_COOLDOWN", "回帖过于频繁，请稍候再试（冷却时间8秒）", 429);
        }
        // 使用加锁后的最新计数，避免 MySQL 可重复读快照导致并发回复重号。
        int floor = topic.getReplyCount() + 1;
        long now = System.currentTimeMillis();
        GuildInfo guildInfo = getPlayerGuildInfo(playerId);

        ForumReply reply = new ForumReply();
        reply.setTopicId(topicId);
        reply.setAuthorId(playerId);
        reply.setAuthorName(player.getUsername());
        reply.setAuthorDisplayName(player.getDisplayName());
        reply.setAuthorAvatar(player.getAvatar());
        reply.setAuthorMilitaryRank(player.getMilitaryRank() != null ? player.getMilitaryRank() : 1);
        reply.setAuthorPrestige(player.getPrestige() != null ? player.getPrestige() : 0);
        reply.setAuthorGuildName(guildInfo.guildName());
        reply.setContent(req.content().trim());
        reply.setFloorNumber(floor);
        reply.setQuoteReplyId(req.quoteReplyId());
        reply.setCreatedAt(now);

        reply = replyRepository.save(reply);

        topic.setReplyCount(topic.getReplyCount() + 1);
        topic.setUpdatedAt(now);
        topicRepository.save(topic);

        boardRepository.lockById(topic.getBoardId()).ifPresent(b -> {
            b.setPostCount(b.getPostCount() + 1);
            boardRepository.save(b);
        });

        QuoteReplyDto quote = null;
        if (reply.getQuoteReplyId() != null) {
            ForumReply quoted = replyRepository.findById(reply.getQuoteReplyId()).orElse(null);
            if (quoted != null && topicId.equals(quoted.getTopicId())) {
                String snippet = quoted.getContent().length() > 60
                        ? quoted.getContent().substring(0, 60) + "..." : quoted.getContent();
                quote = new QuoteReplyDto(quoted.getId(), quoted.getAuthorDisplayName() != null ? quoted.getAuthorDisplayName() : quoted.getAuthorName(), quoted.getFloorNumber(), snippet);
            }
        }

        VipIdentity vip = toVipIdentity(player);
        return new ReplyDto(
                reply.getId(),
                reply.getTopicId(),
                reply.getAuthorId(),
                reply.getAuthorName(),
                reply.getAuthorDisplayName(),
                reply.getAuthorAvatar(),
                vip.level(),
                vip.visible(),
                reply.getAuthorMilitaryRank(),
                MilitaryRankDef.getRankName(reply.getAuthorMilitaryRank()),
                reply.getAuthorPrestige(),
                reply.getAuthorGuildName(),
                reply.getContent(),
                reply.getFloorNumber(),
                quote,
                reply.getLikeCount(),
                false,
                false,
                reply.getCreatedAt()
        );
    }

    /** 点赞与回复、删帖共用主题行锁，保证目标存在且并发统计不覆盖正文状态。 */
    @Transactional
    public Map<String, Object> toggleLike(Long playerId, String targetType, Long targetId) {
        String cleanType = targetType == null ? "" : targetType.toUpperCase(Locale.ROOT);
        if (!"TOPIC".equals(cleanType) && !"REPLY".equals(cleanType)) {
            throw new IllegalArgumentException("点赞目标类型无效");
        }

        Long topicId = "TOPIC".equals(cleanType) ? targetId : replyRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("回复不存在或已被删除")).getTopicId();
        topicRepository.lockById(topicId).orElseThrow(() -> new IllegalArgumentException("话题不存在或已被删除"));

        Optional<ForumLike> existing = likeRepository.findByTargetTypeAndTargetIdAndPlayerId(cleanType, targetId, playerId);
        boolean likedNow;
        int newCount;

        if (existing.isPresent()) {
            likeRepository.delete(existing.get());
            likedNow = false;
        } else {
            ForumLike fl = new ForumLike();
            fl.setTargetType(cleanType);
            fl.setTargetId(targetId);
            fl.setPlayerId(playerId);
            fl.setCreatedAt(System.currentTimeMillis());
            likeRepository.save(fl);
            likedNow = true;
        }

        newCount = likeRepository.countByTargetTypeAndTargetId(cleanType, targetId);

        if ("TOPIC".equals(cleanType)) {
            topicRepository.findById(targetId).ifPresent(t -> {
                t.setLikeCount(newCount);
                topicRepository.save(t);
            });
        } else {
            replyRepository.findById(targetId).ifPresent(r -> {
                r.setLikeCount(newCount);
                replyRepository.save(r);
            });
        }

        return Map.of("liked", likedNow, "count", newCount);
    }

    @Transactional
    public Map<String, Object> acceptBounty(Long playerId, Long topicId, Long replyId) {
        ForumTopic topic = topicRepository.lockById(topicId)
                .orElseThrow(() -> new IllegalArgumentException("话题不存在"));

        if (!playerId.equals(topic.getAuthorId())) {
            throw new IllegalArgumentException("只有悬赏发起人有权采纳答案");
        }
        if (!"BOUNTY".equals(topic.getTopicType()) || !"OPEN".equals(topic.getBountyStatus())) {
            throw new IllegalArgumentException("该话题非有效悬赏或已被采纳解决");
        }

        ForumReply reply = replyRepository.findById(replyId)
                .orElseThrow(() -> new IllegalArgumentException("指定楼层不存在"));

        if (!topicId.equals(reply.getTopicId())) {
            throw new IllegalArgumentException("该回复不属于本悬赏话题");
        }
        if (playerId.equals(reply.getAuthorId())) {
            throw new IllegalArgumentException("不能采纳自己的回复作为最佳答案");
        }

        // 只有主城金库可用才结案；加锁避免不同悬赏同时奖励同一玩家时丢失黄金。
        if (resourcesRepository.findByPlayerIdAndCitySlot(reply.getAuthorId(), 0).isEmpty())
            throw new IllegalArgumentException("解答者主城金库不可用，暂无法结算");
        resourceEconomy.beforeResourceChange(reply.getAuthorId(), 0);
        Resources solverRes = resourcesRepository.findCityForTreatment(reply.getAuthorId(), 0)
                .orElseThrow(() -> new IllegalArgumentException("解答者主城金库不可用，暂无法结算"));
        reply.setIsAcceptedBounty(true);
        replyRepository.save(reply);

        topic.setBountyStatus("SOLVED");
        topic.setBountyAcceptedReplyId(replyId);
        topicRepository.save(topic);

        // 奖励结算给答题者
        int bountyGold = topic.getBountyGold();
        solverRes.addResource("gold", bountyGold);
        resourcesRepository.save(solverRes);

        return Map.of("success", true, "bountyGold", bountyGold, "solverId", reply.getAuthorId());
    }

    @Transactional
    public Map<String, Object> tipTopic(Long playerId, Long topicId, TipRequest req) {
        if (req.amountGold() == null || req.amountGold() <= 0) {
            throw new IllegalArgumentException("打赏金额必须大于0");
        }

        ForumTopic topic = topicRepository.lockById(topicId)
                .orElseThrow(() -> new IllegalArgumentException("话题不存在"));

        if (playerId.equals(topic.getAuthorId())) {
            throw new IllegalArgumentException("不能打赏自己发布的文章");
        }

        Player sender = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("打赏人不存在"));

        // 论坛货币固定走双方主城；先结清旧账，打赏不能倒付此前的军官工资。
        new TreeSet<>(List.of(playerId, topic.getAuthorId())).forEach(id -> resourceEconomy.beforeResourceChange(id, 0));
        Resources senderRes = resourcesRepository.findCityForTreatment(playerId, 0)
                .orElseThrow(() -> new IllegalStateException("金库资源异常"));

        long currentGold = senderRes.getGold() != null ? senderRes.getGold() : 0L;
        if (currentGold < req.amountGold()) {
            throw new IllegalArgumentException("黄金储备不足，当前拥有 " + currentGold + "，打赏需要 " + req.amountGold());
        }

        senderRes.setGold(currentGold - req.amountGold());
        resourcesRepository.save(senderRes);

        Resources authorRes = resourcesRepository.findCityForTreatment(topic.getAuthorId(), 0)
                .orElseThrow(() -> new IllegalArgumentException("作者主城金库不可用，暂无法打赏"));
        authorRes.addResource("gold", req.amountGold());
        resourcesRepository.save(authorRes);

        ForumTip tip = new ForumTip();
        tip.setTopicId(topicId);
        tip.setSenderId(playerId);
        tip.setSenderName(sender.getDisplayName() != null ? sender.getDisplayName() : sender.getUsername());
        tip.setAmountGold(req.amountGold());
        tip.setMessage(req.message() != null ? req.message().trim() : "");
        tip.setCreatedAt(System.currentTimeMillis());
        tipRepository.save(tip);

        topic.setTipCount(topic.getTipCount() + 1);
        topic.setTipTotalGold(Math.addExact(topic.getTipTotalGold() == null ? 0L : topic.getTipTotalGold(), req.amountGold().longValue()));
        topicRepository.save(topic);

        return Map.of("success", true, "amountGold", req.amountGold(), "totalTips", topic.getTipCount(), "totalGold", topic.getTipTotalGold());
    }

    /** 删帖清理从属互动并修正统计；仅退回未采纳悬赏，不撤销已完成的奖励与打赏。 */
    @Transactional
    public Map<String, Object> moderateTopic(Long actorPlayerId, Long topicId, AdminTopicActionRequest req, boolean isAdmin) {
        ForumTopic topic = topicRepository.lockById(topicId)
                .orElseThrow(() -> new IllegalArgumentException("话题不存在"));

        String action = req.action().toUpperCase();
        if ("DELETE".equals(action)) {
            if (!isAdmin && !actorPlayerId.equals(topic.getAuthorId())) {
                throw new IllegalArgumentException("权限不足，仅作者或管理员可删除话题");
            }
            if ("BOUNTY".equals(topic.getTopicType()) && "OPEN".equals(topic.getBountyStatus())) {
                resourceEconomy.beforeResourceChange(topic.getAuthorId(), 0);
                Resources authorRes = resourcesRepository.findCityForTreatment(topic.getAuthorId(), 0)
                        .orElseThrow(() -> new IllegalArgumentException("发帖人主城金库不可用，暂无法退还悬赏"));
                authorRes.addResource("gold", topic.getBountyGold());
                resourcesRepository.save(authorRes);
            }
            int removedReplies = replyRepository.countByTopicId(topicId);
            likeRepository.deleteForTopic(topicId);
            replyRepository.deleteByTopicId(topicId);
            tipRepository.deleteByTopicId(topicId);
            topicRepository.delete(topic);
            boardRepository.lockById(topic.getBoardId()).ifPresent(b -> {
                b.setTopicCount(Math.max(0, b.getTopicCount() - 1));
                b.setPostCount(Math.max(0, b.getPostCount() - removedReplies));
                boardRepository.save(b);
            });
            return Map.of("success", true, "action", "DELETED");
        }

        if (!isAdmin) {
            throw new IllegalArgumentException("权限不足，仅管理员可执行置顶/加精/锁定操作");
        }

        switch (action) {
            case "PIN" -> topic.setIsPinned(true);
            case "UNPIN" -> topic.setIsPinned(false);
            case "ESSENCE" -> topic.setIsEssence(true);
            case "UNESSENCE" -> topic.setIsEssence(false);
            case "LOCK" -> topic.setIsLocked(true);
            case "UNLOCK" -> topic.setIsLocked(false);
            default -> throw new IllegalArgumentException("未知操作: " + action);
        }

        topicRepository.save(topic);
        return Map.of("success", true, "action", action);
    }

    /** 论坛登录资料直接读取游戏账号的 VIP 等级与徽章开关，SSO 和账号登录共用此契约。 */
    public ForumProfileDto getProfile(Long playerId) {
        Player p = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        GuildInfo guildInfo = getPlayerGuildInfo(playerId);
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElse(null);
        int gold = (int) Math.min(Integer.MAX_VALUE, res != null && res.getGold() != null ? res.getGold() : 0L);
        int rank = p.getMilitaryRank() != null ? p.getMilitaryRank() : 1;
        int prestige = p.getPrestige() != null ? p.getPrestige() : 0;

        boolean isAdmin = checkIsAdmin(p);

        VipIdentity vip = toVipIdentity(p);
        return new ForumProfileDto(
                p.getId(),
                p.getUsername(),
                p.getDisplayName(),
                p.getAvatar(),
                vip.level(),
                vip.visible(),
                rank,
                MilitaryRankDef.getRankName(rank),
                prestige,
                guildInfo.guildId(),
                guildInfo.guildName(),
                guildInfo.guildRole(),
                gold,
                p.getCityName(),
                p.getCityPosX(),
                p.getCityPosY(),
                prestige >= 50,
                prestige >= 10,
                isAdmin
        );
    }

    public Page<TopicSummaryDto> getMyTopics(Long playerId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        Page<ForumTopic> topics = topicRepository.findByAuthorIdOrderByCreatedAtDesc(playerId, pageable);
        Map<Long, VipIdentity> authors = getAuthorVipIdentities(topics.getContent().stream().map(ForumTopic::getAuthorId).toList());
        return topics.map(t -> toSummaryDto(t, authors.getOrDefault(t.getAuthorId(), NO_VIP)));
    }

    public Optional<Long> getOptionalCurrentPlayerId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal userPrincipal)) {
            return Optional.empty();
        }
        return Optional.of(userPrincipal.getPlayerId());
    }

    private static final VipIdentity NO_VIP = new VipIdentity(0, false);

    private record VipIdentity(int level, boolean visible) {}

    /** 按当前页去重批量读取账号身份，历史帖子随 VIP 升级或隐藏设置更新，避免逐条查询玩家。 */
    private Map<Long, VipIdentity> getAuthorVipIdentities(Collection<Long> authorIds) {
        List<Long> ids = authorIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, VipIdentity> identities = new HashMap<>();
        for (Player player : playerRepository.findAllById(ids)) {
            identities.put(player.getId(), toVipIdentity(player));
        }
        return identities;
    }

    /** 老账号未设置等级或作者已不存在时，不生成 VIP 标识。 */
    private VipIdentity toVipIdentity(Player player) {
        return player == null ? NO_VIP : new VipIdentity(
                Objects.requireNonNullElse(player.getVipLevel(), 0), player.isVipBadgeVisible());
    }

    /** 单帖详情与发帖响应也读取当前账号身份，不使用发帖时的历史快照。 */
    private TopicSummaryDto toSummaryDto(ForumTopic t) {
        Player author = t.getAuthorId() != null ? playerRepository.findById(t.getAuthorId()).orElse(null) : null;
        return toSummaryDto(t, toVipIdentity(author));
    }

    private TopicSummaryDto toSummaryDto(ForumTopic t, VipIdentity vip) {
        ForumBoard b = boardRepository.findById(t.getBoardId()).orElse(null);
        int rank = t.getAuthorMilitaryRank() != null ? t.getAuthorMilitaryRank() : 1;
        return new TopicSummaryDto(
                t.getId(),
                t.getBoardId(),
                b != null ? b.getName() : "综合板块",
                b != null ? b.getBoardKey() : "general",
                t.getAuthorId(),
                t.getAuthorName(),
                t.getAuthorDisplayName(),
                t.getAuthorAvatar(),
                vip.level(),
                vip.visible(),
                rank,
                MilitaryRankDef.getRankName(rank),
                t.getAuthorPrestige(),
                t.getAuthorGuildName(),
                t.getTitle(),
                t.getTopicType(),
                t.getBountyGold(),
                t.getBountyStatus(),
                t.getBattleReportId(),
                t.getViewCount(),
                t.getReplyCount(),
                t.getLikeCount(),
                t.getTipCount(),
                t.getTipTotalGold(),
                t.getIsPinned(),
                t.getIsEssence(),
                t.getIsLocked(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }

    private GuildInfo getPlayerGuildInfo(Long playerId) {
        Optional<GuildMember> gm = guildMemberRepository.findByPlayerId(playerId);
        if (gm.isEmpty()) {
            return new GuildInfo(null, null, null);
        }
        Guild g = guildRepository.findById(gm.get().getGuildId()).orElse(null);
        String guildName = g != null ? g.getName() : null;
        return new GuildInfo(gm.get().getGuildId(), guildName, gm.get().getRole());
    }

    private boolean checkIsAdmin(Player p) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private record GuildInfo(Long guildId, String guildName, String guildRole) {}
}
