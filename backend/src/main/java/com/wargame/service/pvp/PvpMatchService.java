package com.wargame.service.pvp;

import com.wargame.config.GameServerIdentity;
import com.wargame.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** 有界索引扫描，只读取票据估值；随机只发生在最合适的五名合格真人中。 */
@Service
@RequiredArgsConstructor
public class PvpMatchService {
    private final PvpMatchTicketRepository tickets;
    private final PvpPracticeService practice;
    private final GameServerIdentity region;
    public int match() {
        int matched = 0; long now = System.currentTimeMillis();
        for (var anchor : tickets.findByRegionAndStatusOrderByQueuedAtAsc(region.id(), "QUEUED", PageRequest.of(0, 50))) {
            if (anchor.getLeaseUntil() <= now || anchor.getQueuedAt() + PvpPracticeService.SEARCH_MS <= now) { practice.expireTicket(anchor.getId()); continue; }
            long wait = now - anchor.getQueuedAt(); double window = PvpPracticeService.powerWindow(wait); int rw = PvpPracticeService.ratingWindow(wait);
            for (int attempt = 0; attempt < 3; attempt++) {
                var candidates = new ArrayList<>(tickets.candidates(anchor.getRegion(), anchor.getBattlefield(), anchor.getRulesVersion(), anchor.getPowerModelVersion(), now,
                        (long) Math.ceil(anchor.getPower() / (1 + window)), (long) Math.floor(anchor.getPower() * (1 + window)),
                        anchor.getRating() - rw, anchor.getRating() + rw, PageRequest.of(0, 100)));
                candidates.removeIf(c -> !PvpPracticeService.eligible(anchor, c, now));
                Map<Long, Integer> penalties = practice.repeatPenalties(anchor.getPlayerId());
                candidates.sort(Comparator.<com.wargame.model.entity.PvpMatchTicket>comparingInt(c -> c.isPlacement() == anchor.isPlacement() ? 0 : 1)
                        .thenComparingDouble(c -> Math.abs(Math.log((double) c.getPower() / anchor.getPower()))
                        + Math.abs(c.getRating() - anchor.getRating()) / 1000.0
                        + (wait < 120000 ? penalties.getOrDefault(c.getPlayerId(), 0) * .25 : 0)));
                if (candidates.isEmpty()) break;
                var chosen = candidates.get(ThreadLocalRandom.current().nextInt(Math.min(5, candidates.size())));
                if (practice.pair(anchor.getId(), chosen.getId())) { matched++; break; }
                // 双方认领失败后重读索引候选，最多三次，避免用旧列表反复抢已经配对的票据。
            }
        }
        return matched;
    }
}
