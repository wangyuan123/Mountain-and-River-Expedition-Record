package com.wargame;

import com.wargame.model.dto.ForumDtos.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.ForumService;
import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 手工论坛验收专用 H2 内存库；不连接现有 MySQL、不生成世界地图城市。 */
public class ForumLocalHarness {
    public static void main(String[] args) {
        var app = new SpringApplication(WargameApplication.class);
        app.setAdditionalProfiles("test");
        var context = app.run(args);
        var players = context.getBean(PlayerRepository.class);
        var resources = context.getBean(ResourcesRepository.class);
        var password = context.getBean(PasswordEncoder.class);
        var forum = context.getBean(ForumService.class);
        var board = forum.getBoards().get(0);
        for (int i = 0; i < 24; i++) {
            Player p = new Player(); p.setUsername("forum_test_" + i); p.setDisplayName("论坛验收" + i);
            p.setPasswordHash(password.encode("ForumTest20261029!")); p.setPrestige(i == 23 ? 9 : 100);
            p = players.save(p);
            Resources r = new Resources(); r.setPlayerId(p.getId()); r.setCitySlot(0);
            r.setFood(1000); r.setSteel(1000); r.setOil(1000); r.setRare(1000); r.setGold(10000); r.setDiamond(0);
            resources.save(r);
        }
        var author = players.findByUsername("forum_test_0").orElseThrow();
        var topic = forum.createTopic(author.getId(), new CreateTopicRequest(board.getId(), "【分页验收】20条回复后的新回复定位", "合成测试内容，验证发帖、引用、分页与保存。", "NORMAL", 0, null));
        for (int i = 1; i <= 20; i++) {
            var p = players.findByUsername("forum_test_" + i).orElseThrow();
            forum.createReply(p.getId(), topic.id(), new CreateReplyRequest("合成分页回复 " + i, null));
        }
        System.out.println("FORUM_TEST_READY: H2 isolated; forum_test_0 .. forum_test_23; password=ForumTest20261029!");
    }
}
