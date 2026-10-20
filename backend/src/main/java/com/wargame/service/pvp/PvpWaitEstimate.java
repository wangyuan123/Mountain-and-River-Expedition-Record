package com.wargame.service.pvp;

import com.wargame.util.GameClock;
import com.wargame.repository.PvpMatchTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 只以同类别最近一小时的成功入场样本估计等待，30份以下不显示预测。 */
@Service
@RequiredArgsConstructor
public class PvpWaitEstimate {
    private final PvpMatchTicketRepository tickets;
    private record Cached(long until, Map<String, Object> value) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public Map<String, Object> estimate(String region, String field, String rules) {
        String key = region + ":" + field + ":" + rules;
        long now = GameClock.now();
        return cache.compute(key, (k, old) -> {
            if (old != null && old.until > now) return old;
            var waits = new ArrayList<>(tickets.recentWaits(region, field, rules, now - 3600000, PageRequest.of(0, 100)));
            waits.removeIf(n -> n < 0 || n >= PvpPracticeService.SEARCH_MS);
            Collections.sort(waits);
            Map<String, Object> value = waits.size() < 30 ? Map.of("available", false) : Map.of("available", true, "samples", waits.size(),
                    "medianMs", waits.get(waits.size() / 2), "lowerMs", waits.get(waits.size() / 4), "upperMs", waits.get(waits.size() * 3 / 4));
            return new Cached(now + 30000, value);
        }).value;
    }
}
