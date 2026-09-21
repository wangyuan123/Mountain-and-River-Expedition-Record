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
    void declareWar_sendsSystemMailToBothAttackerAndDefender() {
        Player attacker = createTestPlayer("attacker_user", 30);
        Player defender = createTestPlayer("defender_user", 30);

        PlayerCity targetCity = new PlayerCity();
        targetCity.setWorldId(1L);
        targetCity.setName("铁血前哨");
        targetCity.setOwnerId(defender.getId());
        targetCity.setX(45);
        targetCity.setY(88);
        targetCity = playerCityRepository.save(targetCity);

        Map<String, Object> declareRes = worldService.declareWar(attacker.getId(), targetCity.getId());
        assertTrue((Boolean) declareRes.get("success"), "宣战应该成功");

        // 验证世界频道广播包含目标玩家而非仅城市名
        var chatHistory = chatService.history();
        assertFalse(chatHistory.isEmpty(), "世界频道应有广播消息");
        var lastChat = chatHistory.get(chatHistory.size() - 1);
        assertEquals("系统", lastChat.username());
        assertTrue(lastChat.content().contains("attacker_user 对玩家「defender_user」宣战!"),
                "广播文案应包含 'attacker_user 对玩家「defender_user」宣战!'，实际为: " + lastChat.content());
        assertFalse(lastChat.content().contains("对 铁血前哨 宣战"), "广播文案不应为对城市宣战");

        // 验证攻击者（宣战发起方）收到的系统邮件
        List<Map<String, Object>> attackerMails = mailService.listSystem(attacker.getId());
        assertEquals(1, attackerMails.size(), "发起方应收到1封系统宣战邮件");
        Map<String, Object> attackerMail = attackerMails.get(0);
        assertEquals("系统", attackerMail.get("from"));
        assertEquals("combat", attackerMail.get("type"));
        String attackerSubj = (String) attackerMail.get("subject");
        String attackerBody = (String) attackerMail.get("body");
        assertTrue(attackerSubj.contains("宣战公告") && attackerSubj.contains("defender_user"), "邮件标题应包含宣战公告及目标玩家名");
        assertTrue(attackerBody.contains("铁血前哨"), "邮件正文应包含目标城市名称");
        assertTrue(attackerBody.contains("45, 88"), "邮件正文应包含目标坐标");
        assertTrue(attackerBody.contains("备战时间：2 小时"), "邮件正文应包含备战时间说明");
        assertTrue(attackerBody.contains("战术作战指示"), "邮件正文应包含战术作战指示");

        // 验证防守方（被宣战方）收到的系统邮件
        List<Map<String, Object>> defenderMails = mailService.listSystem(defender.getId());
        assertEquals(1, defenderMails.size(), "防守方应收到1封系统警报邮件");
        Map<String, Object> defenderMail = defenderMails.get(0);
        assertEquals("系统", defenderMail.get("from"));
        assertEquals("combat", defenderMail.get("type"));
        String defenderSubj = (String) defenderMail.get("subject");
        String defenderBody = (String) defenderMail.get("body");
        assertTrue(defenderSubj.contains("战争警报") && defenderSubj.contains("attacker_user"), "邮件标题应包含战争警报及发起玩家名");
        assertTrue(defenderBody.contains("防守应对建议"), "防守方邮件正文应包含防御建议");
    }
}
