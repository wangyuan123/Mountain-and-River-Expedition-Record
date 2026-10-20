package com.wargame.service.pvp;

import com.wargame.util.GameClock;
import com.wargame.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 调度只扫描演习截止索引，多个实例由统一账号锁和回合唯一约束防重。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class PvpPracticeScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(PvpPracticeScheduler.class);
    private final PvpMatchService matcher;
    private final PvpPracticeService practice;
    private final PvpPracticeSessionRepository sessions;
    private final PvpMatchTicketRepository tickets;
    @Scheduled(fixedDelayString = "${game.pvp.interval:1000}")
    public void tick() {
        long now = GameClock.now();
        for (String id : tickets.expired(now, now - PvpPracticeService.SEARCH_MS, PageRequest.of(0, 100))) {
            try { practice.expireTicket(id); } catch (Exception e) { LOG.warn("演习票据处理失败 {}", id, e); }
        }
        for (String id : sessions.due(now, PageRequest.of(0, 100))) {
            try { practice.advance(id); }
            catch (org.springframework.dao.TransientDataAccessException e) { LOG.warn("演习锁冲突，下一轮重读 {}", id); }
            catch (Exception e) { LOG.error("演习恢复失败 {}", id, e); try { practice.voidSession(id); } catch (Exception ignored) { LOG.error("演习作废等待重试 {}", id); } }
        }
        try { matcher.match(); } catch (Exception e) { LOG.warn("演习匹配等待下一轮重试", e); }
    }
}
