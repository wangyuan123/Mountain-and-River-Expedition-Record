package com.wargame;

import com.wargame.model.dto.ForumDtos.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.ForumService;
import com.wargame.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 覆盖真实 HTTP 错误、发布后读取、引用归属及回复分页；每例事务回滚。 */
@AutoConfigureMockMvc
class ForumPostingTest extends BaseServiceTest {
    @Autowired ForumService forum;
    @Autowired ForumBoardRepository boards;
    @Autowired ForumReplyRepository replies;
    @Autowired ForumTopicRepository topics;
    @Autowired ForumLikeRepository likes;
    @Autowired MockMvc http;
    @Autowired JwtUtil jwt;

    private Player writer(String name, int prestige) {
        Player p = createTestPlayer(name, 30); p.setPrestige(prestige); return playerRepository.save(p);
    }
    private TopicSummaryDto topic(Player p) {
        return forum.createTopic(p.getId(), new CreateTopicRequest(boards.findByBoardKey("strategy").orElseThrow().getId(),
                "论坛流程验收", "第一行\n第二行 [coord:5,387] <script>示例</script>", "NORMAL", 0, null));
    }
    private String auth(Player p) { return "Bearer " + jwt.generateToken(p.getUsername(), p.getId()); }

    @Test void replyPrestigeMatchesProfileAndRejectsBeforeWriting() {
        var topic = topic(writer("threshold-author", 100));
        Player p = writer("threshold-reader", 9);
        assertFalse(forum.getProfile(p.getId()).canReply());
        assertThrows(IllegalArgumentException.class, () -> forum.createReply(p.getId(), topic.id(), new CreateReplyRequest("低声望回复", null)));
        assertEquals(0, replies.countByTopicId(topic.id()));
        p.setPrestige(10); playerRepository.save(p);
        assertEquals(1, forum.createReply(p.getId(), topic.id(), new CreateReplyRequest("达标回复", null)).floorNumber());
    }

    @Test void invalidInputDoesNotConsumeSuccessfulPostOrReplyCooldown() {
        Player p = writer("invalid-author", 100);
        assertThrows(IllegalArgumentException.class, () -> forum.createTopic(p.getId(), new CreateTopicRequest(null, "标题", "正文", "NORMAL", 0, null)));
        var topic = topic(p);
        assertThrows(IllegalArgumentException.class, () -> forum.createReply(p.getId(), topic.id(), new CreateReplyRequest("  ", null)));
        assertEquals(1, forum.createReply(p.getId(), topic.id(), new CreateReplyRequest("有效回复", null)).floorNumber());
    }

    @Test void quotesMustExistAndBelongToCurrentTopic() {
        var first = topic(writer("quote-first", 100));
        var second = topic(writer("quote-second", 100));
        var original = forum.createReply(writer("quote-source", 100).getId(), first.id(), new CreateReplyRequest("原始回复", null));
        var replier = writer("quote-target", 100);
        assertThrows(IllegalArgumentException.class, () -> forum.createReply(replier.getId(), second.id(), new CreateReplyRequest("跨帖引用", original.id())));
        assertThrows(IllegalArgumentException.class, () -> forum.createReply(replier.getId(), first.id(), new CreateReplyRequest("不存在的引用", Long.MAX_VALUE)));
        var quoted = forum.createReply(replier.getId(), first.id(), new CreateReplyRequest("正常引用", original.id()));
        assertEquals(original.id(), quoted.quoteReply().id());
        assertEquals("原始回复", forum.getReplies(first.id(), 0, 20).getContent().get(1).quoteReply().snippet());
    }

    @Test void paginationPreservesContentCountsAndOrder() {
        Player author = writer("paging-author", 100);
        var topic = topic(author);
        for (int i = 1; i <= 21; i++) forum.createReply(writer("paging-" + i, 100).getId(), topic.id(), new CreateReplyRequest("第" + i + "条\n换行", null));
        var first = forum.getReplies(topic.id(), 0, 20);
        var last = forum.getReplies(topic.id(), 1, 20);
        assertEquals(21, first.getTotalElements()); assertEquals(2, first.getTotalPages());
        assertEquals(20, first.getContent().size()); assertEquals(21, last.getContent().get(0).floorNumber());
        assertEquals("第21条\n换行", last.getContent().get(0).content());
        assertEquals(21, forum.getTopicDetail(topic.id()).topic().replyCount());
        assertTrue(forum.getTopicDetail(topic.id()).content().contains("<script>示例</script>"));
        assertEquals(topic.id(), forum.getMyTopics(author.getId(), 0, 15).getContent().get(0).id());
        assertEquals(topic.id(), forum.getTopics(null, null, null, "论坛流程", 0, 15).getContent().get(0).id());
    }

    @Test void cooldownAndLockReturnActionableHttpErrors() throws Exception {
        Player author = writer("http-author", 100);
        var topic = topic(author);
        http.perform(post("/api/forum/topics").header("Authorization", auth(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"boardId\":" + topic.boardId() + ",\"title\":\"重复提交\",\"content\":\"正文\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("15秒")));
        forum.moderateTopic(author.getId(), topic.id(), new AdminTopicActionRequest("LOCK"), true);
        http.perform(post("/api/forum/topics/" + topic.id() + "/replies").header("Authorization", auth(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"不能回复\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("锁定")));
    }

    @Test void invalidTypesAndBountiesAreRejectedWithoutDebit() {
        Player p = writer("invalid-bounty", 100);
        Long board = boards.findByBoardKey("strategy").orElseThrow().getId();
        long gold = resourcesRepository.findByPlayerIdAndCitySlot(p.getId(), 0).orElseThrow().getGold();
        assertThrows(IllegalArgumentException.class, () -> forum.createTopic(p.getId(), new CreateTopicRequest(board, "错误类型", "正文", "OTHER", 0, null)));
        assertThrows(IllegalArgumentException.class, () -> forum.createTopic(p.getId(), new CreateTopicRequest(board, "错误悬赏", "正文", "BOUNTY", -1, null)));
        assertEquals(gold, resourcesRepository.findByPlayerIdAndCitySlot(p.getId(), 0).orElseThrow().getGold());
        assertNotNull(topic(p).id());
    }

    @Test void missingBoardIsInputErrorAndUnauthenticatedCannotWrite() throws Exception {
        Player p = writer("http-invalid", 100);
        http.perform(post("/api/forum/topics").header("Authorization", auth(p)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"无板块\",\"content\":\"正文\"}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/forum/topics").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void deletingTopicRemovesRepliesAndTheirLikesAndUpdatesBoardCounts() {
        Player author = writer("delete-author", 100), reader = writer("delete-reader", 100);
        var topic = topic(author);
        var reply = forum.createReply(reader.getId(), topic.id(), new CreateReplyRequest("待清理回复", null));
        forum.toggleLike(reader.getId(), "TOPIC", topic.id());
        forum.toggleLike(author.getId(), "REPLY", reply.id());
        assertThrows(IllegalArgumentException.class, () -> forum.moderateTopic(reader.getId(), topic.id(), new AdminTopicActionRequest("DELETE"), false));
        assertEquals(1, replies.countByTopicId(topic.id()));
        forum.moderateTopic(author.getId(), topic.id(), new AdminTopicActionRequest("DELETE"), false);
        assertFalse(topics.existsById(topic.id()));
        assertEquals(0, replies.countByTopicId(topic.id()));
        assertEquals(0, boards.findById(topic.boardId()).orElseThrow().getPostCount());
        assertEquals(0, boards.findById(topic.boardId()).orElseThrow().getTopicCount());
        assertEquals(0, likes.countByTargetTypeAndTargetId("TOPIC", topic.id()));
        assertEquals(0, likes.countByTargetTypeAndTargetId("REPLY", reply.id()));
        assertThrows(IllegalArgumentException.class, () -> forum.toggleLike(reader.getId(), "TOPIC", topic.id()));
        assertThrows(IllegalArgumentException.class, () -> forum.toggleLike(reader.getId(), "REPLY", reply.id()));
    }

    @Test void deletingOpenBountyRefundsEscrowButSolvedBountyCannotPayTwice() {
        Player author = writer("refund-author", 100), solver = writer("refund-solver", 100);
        Long board = boards.findByBoardKey("strategy").orElseThrow().getId();
        long before = forum.getProfile(author.getId()).gold();
        var open = forum.createTopic(author.getId(), new CreateTopicRequest(board, "未结悬赏", "正文", "BOUNTY", 100, null));
        assertEquals(before - 100, forum.getProfile(author.getId()).gold().longValue());
        forum.moderateTopic(author.getId(), open.id(), new AdminTopicActionRequest("DELETE"), false);
        assertEquals(before, forum.getProfile(author.getId()).gold().longValue());
        var solved = forum.createTopic(solver.getId(), new CreateTopicRequest(board, "已结悬赏", "正文", "BOUNTY", 100, null));
        var answer = forum.createReply(author.getId(), solved.id(), new CreateReplyRequest("有效答案", null));
        assertThrows(IllegalArgumentException.class, () -> forum.acceptBounty(author.getId(), solved.id(), answer.id()));
        forum.acceptBounty(solver.getId(), solved.id(), answer.id());
        assertThrows(IllegalArgumentException.class, () -> forum.acceptBounty(solver.getId(), solved.id(), answer.id()));
        forum.moderateTopic(solver.getId(), solved.id(), new AdminTopicActionRequest("DELETE"), false);
        assertEquals(before + 100, forum.getProfile(author.getId()).gold().longValue());
    }

    @Test void unavailableMainTreasuryDoesNotCloseBountyOrPayAnotherCity() {
        Player author = writer("treasury-author", 100), solver = writer("treasury-solver", 100);
        var bounty = forum.createTopic(author.getId(), new CreateTopicRequest(forum.getBoards().get(0).getId(), "金库异常验收", "正文", "BOUNTY", 100, null));
        var reply = forum.createReply(solver.getId(), bounty.id(), new CreateReplyRequest("答案", null));
        Resources secondary = resourcesRepository.findByPlayerIdAndCitySlot(solver.getId(), 0).orElseThrow();
        long gold = secondary.getGold();
        secondary.setCitySlot(1); resourcesRepository.saveAndFlush(secondary);
        assertThrows(IllegalArgumentException.class, () -> forum.acceptBounty(author.getId(), bounty.id(), reply.id()));
        assertEquals("OPEN", topics.findById(bounty.id()).orElseThrow().getBountyStatus());
        assertFalse(replies.findById(reply.id()).orElseThrow().getIsAcceptedBounty());
        assertEquals(gold, resourcesRepository.findByPlayerIdAndCitySlot(solver.getId(), 1).orElseThrow().getGold().longValue());
    }
}
