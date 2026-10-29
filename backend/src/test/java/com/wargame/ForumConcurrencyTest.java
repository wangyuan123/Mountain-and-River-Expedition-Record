package com.wargame;

import com.wargame.model.dto.ForumDtos.*;
import com.wargame.model.entity.Player;
import com.wargame.repository.ForumTopicRepository;
import com.wargame.service.ForumService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 多个独立提交事务同时回帖，验证楼层和统计不会丢失；数据仅写入 H2 测试库。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ForumConcurrencyTest extends BaseServiceTest {
    @Autowired ForumService forum;
    @Autowired ForumTopicRepository topics;
    @Autowired PlatformTransactionManager transactions;

    @Test void simultaneousRepliesHaveDistinctFloorsAndAccurateCounters() throws Exception {
        var tx = new TransactionTemplate(transactions);
        List<Long> players = new ArrayList<>();
        Long topicId = tx.execute(status -> {
            for (int i = 0; i <= 8; i++) {
                Player p = createTestPlayer("parallel-forum-" + i, 30); p.setPrestige(100);
                players.add(playerRepository.save(p).getId());
            }
            return forum.createTopic(players.get(0), new CreateTopicRequest(forum.getBoards().get(0).getId(), "并发回帖", "事务验收", "NORMAL", 0, null)).id();
        });
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                Long player = players.get(i);
                futures.add(pool.submit(() -> { start.await(); return forum.createReply(player, topicId, new CreateReplyRequest("并发回复", null)).floorNumber(); }));
            }
            start.countDown();
            Set<Integer> floors = new TreeSet<>();
            for (var result : futures) floors.add(result.get(30, TimeUnit.SECONDS));
            assertEquals(Set.of(1,2,3,4,5,6,7,8), floors);
            assertEquals(8, forum.getReplies(topicId, 0, 20).getTotalElements());
            assertEquals(8, forum.getTopicDetail(topicId).topic().replyCount());
            assertEquals(8, forum.getBoards().get(0).getPostCount());
        } finally { pool.shutdownNow(); }
    }
}
