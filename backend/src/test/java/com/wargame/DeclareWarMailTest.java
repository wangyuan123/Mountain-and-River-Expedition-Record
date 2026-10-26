package com.wargame;

import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.service.MailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeclareWarMailTest extends BaseServiceTest {

    @Autowired
    private MailService mailService;

    @Autowired
    private com.wargame.service.ChatService chatService;

    @Test
    void declareWar_withoutCommanderNamesDoesNotExposeLoginNames() {
        Player attacker = createTestPlayer("private_attacker_login", 30);
        Player defender = createTestPlayer("private_defender_login", 30);
        defender.setDisplayName("  ");
        playerRepository.save(defender);
        PlayerCity target = new PlayerCity();
        target.setWorldId(1L);
        target.setOwnerId(defender.getId());
        target.setName("前线城");
        target.setX(45);
        target.setY(88);
        playerCityRepository.save(target);

        assertEquals(true, worldService.declareWar(attacker.getId(), target.getId()).get("success"));
        assertTrue(chatService.history().stream().anyMatch(message -> message.content().equals(
                "⚔ 统帅" + attacker.getId() + " 对玩家「统帅" + defender.getId() + "」宣战!")));
        for (Player player : List.of(attacker, defender)) {
            Map<String, Object> summary = mailService.listSystem(player.getId()).get(0);
            Map<String, Object> detail = mailService.detail(player.getId(), ((Number) summary.get("id")).longValue());
            assertEquals("统帅" + player.getId(), detail.get("to"));
            assertFalse(detail.toString().contains(attacker.getUsername()));
            assertFalse(detail.toString().contains(defender.getUsername()));
        }
    }

    @Test
    void declareWar_sendsSystemMailToBothAttackerAndDefender() {
        Player attacker = createTestPlayer("attacker_user", 30);
        Player defender = createTestPlayer("defender_user", 30);
        attacker.setDisplayName("烈火统帅");
        defender.setDisplayName("山林统帅");
        playerRepository.saveAll(List.of(attacker, defender));

        PlayerCity targetCity = new PlayerCity();
        targetCity.setWorldId(1L);
        targetCity.setName("铁血前哨");
        targetCity.setOwnerId(defender.getId());
        targetCity.setX(45);
        targetCity.setY(88);
        targetCity = playerCityRepository.save(targetCity);

        Map<String, Object> declareRes = worldService.declareWar(attacker.getId(), targetCity.getId());
        assertTrue((Boolean) declareRes.get("success"), "宣战应该成功");
        assertEquals("已宣战! 2小时0分钟0秒后可交战,持续2天0小时0分钟0秒", declareRes.get("message"));

        // 验证世界频道广播包含目标玩家而非仅城市名
        var chatHistory = chatService.history();
        assertFalse(chatHistory.isEmpty(), "世界频道应有广播消息");
        var lastChat = chatHistory.get(chatHistory.size() - 1);
        assertEquals("系统", lastChat.username());
        assertTrue(lastChat.content().contains("烈火统帅 对玩家「山林统帅」宣战!"));
        assertFalse(lastChat.content().contains(attacker.getUsername()));
        assertFalse(lastChat.content().contains(defender.getUsername()));
        assertFalse(lastChat.content().contains("对 铁血前哨 宣战"), "广播文案不应为对城市宣战");

        // 验证攻击者（宣战发起方）收到的系统邮件
        List<Map<String, Object>> attackerMails = mailService.listSystem(attacker.getId());
        assertEquals(1, attackerMails.size(), "发起方应收到1封系统宣战邮件");
        Map<String, Object> attackerMail = mailService.detail(attacker.getId(), (Long) attackerMails.get(0).get("id"));
        assertEquals("系统", attackerMail.get("from"));
        assertEquals("combat", attackerMail.get("type"));
        String attackerSubj = (String) attackerMail.get("subject");
        String attackerBody = (String) attackerMail.get("body");
        assertEquals("烈火统帅", attackerMail.get("to"));
        assertTrue(attackerSubj.contains("宣战公告") && attackerSubj.contains("山林统帅"));
        assertTrue(attackerBody.contains("玩家「山林统帅」"));
        assertFalse(attackerSubj.contains(defender.getUsername()));
        assertFalse(attackerBody.contains(defender.getUsername()));
        assertTrue(attackerBody.contains("铁血前哨"), "邮件正文应包含目标城市名称");
        assertTrue(attackerBody.contains("45, 88"), "邮件正文应包含目标坐标");
        assertTrue(attackerBody.contains("备战时间：2小时0分钟0秒"), "邮件正文应包含统一格式的备战时间说明");
        assertTrue(attackerBody.contains("交战时间：2天0小时0分钟0秒"));
        assertTrue(attackerBody.contains("战术作战指示"), "邮件正文应包含战术作战指示");

        // 验证防守方（被宣战方）收到的系统邮件
        List<Map<String, Object>> defenderMails = mailService.listSystem(defender.getId());
        assertEquals(1, defenderMails.size(), "防守方应收到1封系统警报邮件");
        Map<String, Object> defenderMail = mailService.detail(defender.getId(), (Long) defenderMails.get(0).get("id"));
        assertEquals("系统", defenderMail.get("from"));
        assertEquals("combat", defenderMail.get("type"));
        String defenderSubj = (String) defenderMail.get("subject");
        String defenderBody = (String) defenderMail.get("body");
        assertEquals("山林统帅", defenderMail.get("to"));
        assertTrue(defenderSubj.contains("战争警报") && defenderSubj.contains("烈火统帅"));
        assertTrue(defenderBody.contains("玩家「烈火统帅」"));
        assertFalse(defenderSubj.contains(attacker.getUsername()));
        assertFalse(defenderBody.contains(attacker.getUsername()));
        assertTrue(defenderBody.contains("防守应对建议"), "防守方邮件正文应包含防御建议");
        assertTrue(defenderBody.contains("备战时间：2小时0分钟0秒"));
        assertTrue(defenderBody.contains("交战时间：2天0小时0分钟0秒"));
    }
}
