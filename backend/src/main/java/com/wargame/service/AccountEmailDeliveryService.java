package com.wargame.service;

import com.wargame.config.AccountEmailProperties;
import com.wargame.model.entity.AccountEmailRecords.*;
import com.wargame.model.entity.Player;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 投递使用持久化租约和有界重试；SMTP 错误详情可能包含地址，禁止直接写日志。 */
@Service
public class AccountEmailDeliveryService {
    private final EntityManager em;
    private final AccountEmailVault vault;
    private final AccountEmailProperties config;
    private final AccountMailSender sender;
    private final TransactionTemplate transactions;
    @Value("${game.scheduling.enabled:true}") private boolean schedulingEnabled;

    public AccountEmailDeliveryService(EntityManager em, AccountEmailVault vault, AccountEmailProperties config,
                                       AccountMailSender sender, PlatformTransactionManager manager) {
        this.em = em; this.vault = vault; this.config = config; this.sender = sender;
        transactions = new TransactionTemplate(manager);
    }
    private record Delivery(String id, String email, String subject, String body) {}

    @Scheduled(fixedDelay = 3000, initialDelay = 15000, scheduler = "accountEmailTaskScheduler")
    public void scheduledDelivery() {
        if (!schedulingEnabled) return;
        if (config.available()) deliverPending();
        else transactions.executeWithoutResult(s -> cleanup());
    }

    /** 每轮最多十封；先领取租约、事务外投递，再记录结果。 */
    public void deliverPending() {
        if (!config.available()) return;
        List<String> ids = transactions.execute(s -> em.createQuery("select o.id from AccountEmailOutbox o "
                        + "where o.state < 2 and o.nextAttemptAt <= :now order by o.nextAttemptAt", String.class)
                .setParameter("now", System.currentTimeMillis()).setMaxResults(10).getResultList());
        for (String id : ids) {
            Delivery delivery = transactions.execute(s -> claim(id));
            if (delivery == null) continue;
            boolean delivered = false;
            try { sender.send(vault.decrypt(delivery.email()), delivery.subject(), vault.decrypt(delivery.body())); delivered = true; }
            catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Account email delivery deferred; task={}", id);
            }
            boolean success = delivered;
            transactions.executeWithoutResult(s -> finish(id, success));
        }
        transactions.executeWithoutResult(s -> cleanup());
    }

    private Delivery claim(String id) {
        Outbox mail = em.find(Outbox.class, id, LockModeType.PESSIMISTIC_WRITE);
        long now = System.currentTimeMillis();
        if (mail == null || mail.getState() >= 2 || mail.getNextAttemptAt() > now) return null;
        Challenge challenge = challenge(mail.getChallengeId());
        Player player = mail.getPlayerId() == null ? null : em.find(Player.class, mail.getPlayerId());
        if (now >= mail.getExpiresAt() || mail.getAttempts() >= 3 || mail.getBodyCipher() == null
                || (mail.getChallengeId() != null && (challenge == null || challenge.isConsumed()
                    || (player != null && (player.getAuthVersion() != challenge.getAuthVersion()
                    || player.getRecoveryEmailVersion() != challenge.getEmailVersion()))))
                || (player != null && player.deletionDue(now))) {
            clear(mail, 3); return null;
        }
        mail.setState(1); mail.setAttempts(mail.getAttempts() + 1); mail.setNextAttemptAt(now + 60_000);
        return new Delivery(id, mail.getEmailCipher(), mail.getSubject(), mail.getBodyCipher());
    }
    private Challenge challenge(String id) {
        if (id == null) return null;
        return em.createQuery("select c from AccountEmailChallenge c where c.challengeId = :id", Challenge.class)
                .setParameter("id", id).getResultStream().findFirst().orElse(null);
    }
    private void finish(String id, boolean success) {
        Outbox mail = em.find(Outbox.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (mail == null || mail.getState() != 1) return;
        if (success) {
            Challenge c = challenge(mail.getChallengeId());
            if (c != null && c.getExpiresAt() > System.currentTimeMillis()) c.setSent(true);
            clear(mail, 2);
        } else if (mail.getAttempts() >= 3 || mail.getExpiresAt() <= System.currentTimeMillis()) clear(mail, 3);
        else { mail.setState(0); mail.setNextAttemptAt(System.currentTimeMillis() + 15_000); }
    }
    private void clear(Outbox mail, int state) {
        mail.setState(state); mail.setBodyCipher(null); mail.setEmailCipher(null);
    }
    private void cleanup() {
        long now = System.currentTimeMillis();
        em.createNativeQuery("insert ignore into account_email_gate (id) values (1)").executeUpdate();
        em.find(Gate.class, 1, LockModeType.PESSIMISTIC_WRITE);
        for (Outbox mail : em.createQuery("select o from AccountEmailOutbox o where o.expiresAt <= :now "
                        + "and (o.bodyCipher is not null or o.expiresAt < :cutoff) order by o.expiresAt", Outbox.class)
                .setParameter("now", now).setParameter("cutoff", now - 86_400_000L).setMaxResults(500).getResultList()) {
            clear(mail, 3);
            if (mail.getExpiresAt() < now - 86_400_000L) em.remove(mail);
        }
        em.createQuery("delete from AccountEmailChallenge c where c.expiresAt < :cutoff").setParameter("cutoff", now - 86_400_000L).executeUpdate();
        em.createQuery("delete from AccountEmailChange c where c.expiresAt <= :now").setParameter("now", now).executeUpdate();
        em.createQuery("delete from AccountEmailQuota q where q.expiresAt <= :now").setParameter("now", now).executeUpdate();
        // 只清理本功能的最小安全事件，不更改其他后台审计记录的保留策略。
        em.createQuery("delete from AdminOperationLog a where a.actionType in :types and a.createdAt < :cutoff")
                .setParameter("types", List.of("ACCOUNT_EMAIL_BIND", "ACCOUNT_PASSWORD_RESET", "ACCOUNT_EMAIL_CHANGE"))
                .setParameter("cutoff", now - 30L * 86_400_000L).executeUpdate();
    }
}
