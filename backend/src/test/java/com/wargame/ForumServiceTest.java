package com.wargame;

import com.wargame.model.dto.ForumDtos.*;
import com.wargame.model.entity.ForumBoard;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Resources;
import com.wargame.repository.ForumBoardRepository;
import com.wargame.service.ForumService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ForumServiceTest extends BaseServiceTest {

    @Autowired
    private ForumService forumService;

    @Autowired
    private ForumBoardRepository forumBoardRepository;

    @Test
    void testBoardsAndCreateTopicAndReply() {
        List<ForumBoard> boards = forumService.getBoards();
        assertFalse(boards.isEmpty(), "论坛初始板块不应为空");
        ForumBoard strategyBoard = boards.stream()
                .filter(b -> "strategy".equals(b.getBoardKey()))
                .findFirst()
                .orElse(null);
        assertNotNull(strategyBoard, "应存在参谋本部板块");

        // 创建发帖测试玩家
        Player p1 = createTestPlayer("commander_zhang", 30);
        p1.setPrestige(200);
        playerRepository.save(p1);

        // 创建普通帖子
        CreateTopicRequest req = new CreateTopicRequest(
                strategyBoard.getId(),
                "【滇缅防守】关于3级野地开荒的步炮协同配比建议",
                "诸位长官，建议前排配置装甲掷弹兵与轻机枪掩护，后排布置野战炮...",
                "NORMAL",
                0,
                null
        );

        TopicSummaryDto topic = forumService.createTopic(p1.getId(), req);
        assertNotNull(topic.id());
        assertEquals("【滇缅防守】关于3级野地开荒的步炮协同配比建议", topic.title());
        assertEquals(0, topic.replyCount());

        // 详情获取与阅读量自增
        TopicDetailDto detail = forumService.getTopicDetail(topic.id());
        assertEquals(1, detail.topic().viewCount());

        // 创建玩家2进行回复
        Player p2 = createTestPlayer("scout_li", 30);
        p2.setPrestige(100);
        playerRepository.save(p2);

        CreateReplyRequest replyReq = new CreateReplyRequest(
                "长官所见略同！我部昨日以火炮先行轰击，战损减少了四成。",
                null
        );

        ReplyDto reply = forumService.createReply(p2.getId(), topic.id(), replyReq);
        assertNotNull(reply.id());
        assertEquals(1, reply.floorNumber());

        var repliesPage = forumService.getReplies(topic.id(), 0, 10);
        assertEquals(1, repliesPage.getTotalElements());

        // 点赞测试
        Map<String, Object> likeResult = forumService.toggleLike(p2.getId(), "TOPIC", topic.id());
        assertTrue((Boolean) likeResult.get("liked"));
        assertEquals(1, (Integer) likeResult.get("count"));

        // 再次点赞取消
        Map<String, Object> unlikeResult = forumService.toggleLike(p2.getId(), "TOPIC", topic.id());
        assertFalse((Boolean) unlikeResult.get("liked"));
        assertEquals(0, (Integer) unlikeResult.get("count"));
    }

    @Test
    void testBountyAndRewardSettlement() {
        ForumBoard board = forumBoardRepository.findByBoardKey("strategy").orElseThrow();

        // 提问者出资悬赏
        Player asker = createTestPlayer("asker_wang", 30);
        asker.setPrestige(300);
        playerRepository.save(asker);

        Resources askerRes = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(asker.getId()).orElseThrow();
        askerRes.setGold(2000);
        resourcesRepository.save(askerRes);

        CreateTopicRequest bountyReq = new CreateTopicRequest(
                board.getId(),
                "【重金悬赏】求一份低损攻略密支那日军步兵大队的方案",
                "急需克制日军狙击与迫击炮阵地的详细阵容配置！",
                "BOUNTY",
                500,
                null
        );

        TopicSummaryDto bountyTopic = forumService.createTopic(asker.getId(), bountyReq);
        assertEquals("BOUNTY", bountyTopic.topicType());
        assertEquals(500, bountyTopic.bountyGold());
        assertEquals("OPEN", bountyTopic.bountyStatus());

        // 验证提问者黄金已冻结扣除
        Resources askerResAfter = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(asker.getId()).orElseThrow();
        assertEquals(1500, askerResAfter.getGold());

        // 解答者回帖
        Player solver = createTestPlayer("master_sun", 30);
        solver.setPrestige(500);
        playerRepository.save(solver);

        Resources solverRes = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(solver.getId()).orElseThrow();
        solverRes.setGold(100);
        resourcesRepository.save(solverRes);

        ReplyDto reply = forumService.createReply(solver.getId(), bountyTopic.id(), new CreateReplyRequest(
                "以200轻骑兵侧翼穿插，中军配置反坦克炮与山炮齐射即可碾压。",
                null
        ));

        // 提问者采纳最佳答案
        Map<String, Object> acceptRes = forumService.acceptBounty(asker.getId(), bountyTopic.id(), reply.id());
        assertTrue((Boolean) acceptRes.get("success"));

        // 验证解答者黄金已入账（100 + 500 = 600）
        Resources solverResAfter = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(solver.getId()).orElseThrow();
        assertEquals(600, solverResAfter.getGold());
    }

    @Test
    void testTipTopic() {
        ForumBoard board = forumBoardRepository.findByBoardKey("tavern").orElseThrow();

        Player author = createTestPlayer("author_hero", 30);
        author.setPrestige(200);
        playerRepository.save(author);
        Resources authorRes = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(author.getId()).orElseThrow();
        authorRes.setGold(50);
        resourcesRepository.save(authorRes);

        TopicSummaryDto topic = forumService.createTopic(author.getId(), new CreateTopicRequest(
                board.getId(),
                "远征日志：第200师同古保卫战纪实",
                "全师官兵誓与阵地共存亡...",
                "NORMAL",
                0,
                null
        ));

        Player tipper = createTestPlayer("tipper_fan", 30);
        tipper.setPrestige(200);
        playerRepository.save(tipper);
        Resources tipperRes = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(tipper.getId()).orElseThrow();
        tipperRes.setGold(800);
        resourcesRepository.save(tipperRes);

        // 打赏 200 黄金
        Map<String, Object> tipRes = forumService.tipTopic(tipper.getId(), topic.id(), new TipRequest(200, "向英雄部队致敬！"));
        assertTrue((Boolean) tipRes.get("success"));

        // 打赏人扣 200，作者得 200 (50 + 200 = 250)
        Resources tipperAfter = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(tipper.getId()).orElseThrow();
        Resources authorAfter = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(author.getId()).orElseThrow();
        assertEquals(600, tipperAfter.getGold());
        assertEquals(250, authorAfter.getGold());
    }
}
