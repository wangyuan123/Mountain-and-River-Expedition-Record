package com.wargame.service;

import com.wargame.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 有界游标批次，每个账号调用独立事务；失败留待下一轮，不饿死后续账号。 */
@Component
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AccountDeletionScheduler {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionScheduler.class);
    private final PlayerRepository players;
    private final AccountService accounts;
    private long cursor;

    public AccountDeletionScheduler(PlayerRepository players, AccountService accounts) {
        this.players = players;
        this.accounts = accounts;
    }

    @Scheduled(fixedDelayString = "${game.account.cleanup-interval-ms:60000}")
    public void cleanExpired() {
        var ids = players.findExpiredAccountIds(cursor, System.currentTimeMillis(), PageRequest.of(0, 100));
        for (Long id : ids) {
            cursor = id;
            try { accounts.purgeExpiredAccount(id); }
            catch (RuntimeException e) { log.error("账号 {} 注销清理失败，将在下一轮重试", id, e); }
        }
        if (ids.size() < 100) cursor = 0;
    }
}
