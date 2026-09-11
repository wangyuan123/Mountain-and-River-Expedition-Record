package com.wargame;

import com.wargame.model.entity.Mail;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Resources;
import com.wargame.repository.MailRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.service.MailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 邮件服务 smoke test。
 * 验证：
 * <ol>
 *   <li>新玩家种子邮件（系统欢迎/礼包/通告）</li>
 *   <li>玩家互发邮件（带附件，资源扣减）</li>
 *   <li>发件人余额不足时拒绝</li>
 *   <li>收件人领取附件，资源入账</li>
 *   <li>标记已读、未读数</li>
 *   <li>发给自己失败</li>
 *   <li>收件人不存在失败</li>
 * </ol>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class MailServiceTest extends BaseServiceTest {

    @Autowired private MailService mailService;
    @Autowired private MailRepository mailRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private ResourcesRepository resourcesRepository;

    @Test
    void seedForNewPlayer_createsThreeSystemMails() {
        Player p = createTestPlayer("seeduser", 30);
        // GameStateService.initializeNewPlayer -> seedForNewPlayer 在 BaseServiceTest 中未调用
        mailService.seedForNewPlayer(p.getId());

        List<Map<String, Object>> inbox = mailService.listInbox(p.getId());
        assertEquals(3, inbox.size(), "应有 3 封种子邮件");
        long unread = mailService.unreadCount(p.getId());
        assertEquals(1, unread, "欢迎邮件未读, 其它两封已读, 未读应为 1");
    }

    @Test
    void seed_isIdempotent() {
        Player p = createTestPlayer("seeduser2", 30);
        mailService.seedForNewPlayer(p.getId());
        mailService.seedForNewPlayer(p.getId());
        assertEquals(3, mailService.listInbox(p.getId()).size());
    }

    @Test
    void send_playerToPlayer_succeeds() {
        Player a = createTestPlayer("alice", 30);
        Player b = createTestPlayer("bob", 30);
        Map<String, Object> result = mailService.send(
                a.getId(), "bob", "问候", "你好 bob", List.of());
        assertEquals(true, result.get("success"));
        assertEquals(1, mailService.listInbox(b.getId()).size());
        assertEquals(1, mailService.listOutbox(a.getId()).size());
    }

    @Test
    void send_withAttach_deductsSenderResources() {
        Player a = createTestPlayer("alice_attach", 30);
        Player b = createTestPlayer("bob_attach", 30);
        giveResources(a.getId(), 0, 0, 0, 0, 1000);

        mailService.send(a.getId(), "bob_attach", "见面礼",
                "请收下", List.of(Map.of("type", "gold", "qty", 200)));

        Resources ar = getResources(a.getId());
        assertEquals(800, ar.getGold(), "发件人应扣 200 黄金");

        Mail bMail = mailRepository.findByToPlayerIdOrderByCreatedAtDesc(b.getId()).get(0);
        assertEquals(false, bMail.getIsClaimed(), "玩家邮件需要收件人主动领取");
    }

    @Test
    void send_insufficientResources_fails() {
        Player a = createTestPlayer("alice_poor", 30);
        Player b = createTestPlayer("bob_rich", 30);
        giveResources(a.getId(), 0, 0, 0, 0, 0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mailService.send(a.getId(), "bob_rich", "x", "y",
                        List.of(Map.of("type", "gold", "qty", 100))));
        assertTrue(ex.getMessage().contains("资源不足"), "应返回资源不足错误");
    }

    @Test
    void send_toSelf_fails() {
        Player a = createTestPlayer("alice_narcissist", 30);
        assertThrows(IllegalArgumentException.class,
                () -> mailService.send(a.getId(), a.getUsername(), "x", "y", List.of()));
    }

    @Test
    void send_recipientNotFound_fails() {
        Player a = createTestPlayer("alice_ghost", 30);
        assertThrows(IllegalArgumentException.class,
                () -> mailService.send(a.getId(), "不存在的玩家", "x", "y", List.of()));
    }

    @Test
    void claim_depositsResourcesToRecipient() {
        Player a = createTestPlayer("alice_claim", 30);
        Player b = createTestPlayer("bob_claim", 30);
        giveResources(a.getId(), 0, 0, 0, 0, 500);
        giveResources(b.getId(), 0, 0, 0, 0, 0);

        Map<String, Object> sendResult = mailService.send(a.getId(), "bob_claim",
                "红包", "恭喜发财",
                List.of(Map.of("type", "gold", "qty", 300)));
        @SuppressWarnings("unchecked")
        Map<String, Object> mailDto = (Map<String, Object>) sendResult.get("mail");
        Long mailId = ((Number) mailDto.get("id")).longValue();

        // b 领取
        Map<String, Object> claimResult = mailService.claimAttach(b.getId(), mailId);
        assertEquals(true, claimResult.get("success"));

        Resources br = getResources(b.getId());
        assertEquals(300, br.getGold(), "收件人应到账 300 黄金");

        // 重复领取应失败
        assertThrows(IllegalArgumentException.class,
                () -> mailService.claimAttach(b.getId(), mailId));
    }

    @Test
    void markRead_reducesUnread() {
        Player a = createTestPlayer("alice_read", 30);
        Player b = createTestPlayer("bob_read", 30);
        mailService.send(a.getId(), "bob_read", "hi", "hello", List.of());
        assertEquals(1, mailService.unreadCount(b.getId()));

        Mail mail = mailRepository.findByToPlayerIdOrderByCreatedAtDesc(b.getId()).get(0);
        mailService.markRead(b.getId(), mail.getId());
        assertEquals(0, mailService.unreadCount(b.getId()));
    }

    @Test
    void delete_onlyOwner() {
        Player a = createTestPlayer("alice_del", 30);
        Player b = createTestPlayer("bob_del", 30);
        mailService.send(a.getId(), "bob_del", "x", "y", List.of());

        Mail mail = mailRepository.findByToPlayerIdOrderByCreatedAtDesc(b.getId()).get(0);
        // a 不能删 b 的邮件
        mailService.delete(a.getId(), mail.getId());
        assertEquals(1, mailRepository.count(), "a 不应该能删 b 的邮件");

        // b 能删
        mailService.delete(b.getId(), mail.getId());
        assertEquals(0, mailRepository.count());
    }

    @Test
    void sendSystem_createSystemMail() {
        Player p = createTestPlayer("sysuser", 30);
        Mail m = mailService.sendSystem(p.getId(), "战报中心", "combat",
                "战斗报告", "我军获胜", List.of());
        assertNotNull(m.getId());
        assertEquals(true, m.getIsSystem());

        List<Map<String, Object>> sys = mailService.listSystem(p.getId());
        assertEquals(1, sys.size());
    }
}
