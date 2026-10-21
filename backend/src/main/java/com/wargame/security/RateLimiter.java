package com.wargame.security;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 简易内存版限流器。
 * 生产环境应当换成 Redis (Bucket4j / Redisson)，否则重启失效且不支持多实例。
 * 现阶段为单实例 + 防暴力枚举已足够。
 */
@Component
public class RateLimiter {
    private final int maxKeys;
    private final LongSupplier clock;
    private Iterator<Map.Entry<String, Counter>> evictionCursor;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final LongAdder evictedWindows = new LongAdder();
    private final LongAdder capacityRejections = new LongAdder();

    public RateLimiter() { this(100_000, System::currentTimeMillis); }
    RateLimiter(int maxKeys, LongSupplier clock) { this.maxKeys = maxKeys; this.clock = clock; }

    /** True if allowed, false if exceeded. Each window is independent. */
    public synchronized boolean allow(String key, int maxPerWindow, long windowMs) {
        if (windowMs <= 0 || maxPerWindow < 1) return false;
        long now = clock.getAsLong();
        Counter existing = counters.get(key);
        if (existing == null && counters.size() >= maxKeys) evictExpired(now);
        AtomicBoolean allowed = new AtomicBoolean();
        Counter c = counters.compute(key, (k, old) -> {
            if (old == null) {
                if (counters.size() >= maxKeys) return null;
                allowed.set(true);
                return new Counter(now + windowMs, 1);
            }
            if (old.windowEndsAt <= now) {
                allowed.set(true);
                return new Counter(now + windowMs, 1);
            }
            int count = Math.min(Integer.MAX_VALUE - 1, old.count) + 1;
            allowed.set(count <= maxPerWindow);
            return new Counter(old.windowEndsAt, count);
        });
        if (c == null) {
            capacityRejections.increment();
            return false;
        }
        return allowed.get();
    }

    /** Returns remaining seconds until the window resets (best-effort). */
    public long retryAfterSeconds(String key, long windowMs) {
        Counter c = counters.get(key);
        if (c == null) return 0;
        long remaining = c.windowEndsAt - clock.getAsLong();
        if (remaining <= 0) return 0;
        return Math.max(1, (remaining + 999) / 1000);
    }

    /** 每分钟只移除确已过期的窗口；有效窗口不会因容量压力重置。 */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS, scheduler = "retentionTaskScheduler")
    public synchronized void evictExpiredWindows() {
        evictExpired(clock.getAsLong());
        org.slf4j.LoggerFactory.getLogger(RateLimiter.class).info("Rate limiter keys={} expiredEvictions={} capacityRejections={}", keyCount(), evictedWindowCount(), capacityRejectionCount());
    }

    public int keyCount() { return counters.size(); }
    public long evictedWindowCount() { return evictedWindows.sum(); }
    public long capacityRejectionCount() { return capacityRejections.sum(); }

    private void evictExpired(long now) {
        if (evictionCursor == null || !evictionCursor.hasNext()) evictionCursor = counters.entrySet().iterator();
        for (int scanned = 0; scanned < 2000 && evictionCursor.hasNext(); scanned++) {
            var entry = evictionCursor.next();
            Counter counter = entry.getValue();
            if (counter.windowEndsAt <= now && counters.remove(entry.getKey(), counter)) evictedWindows.increment();
        }
    }

    private static class Counter {
        final long windowEndsAt;
        final int count;
        Counter(long windowEndsAt, int initialCount) {
            this.windowEndsAt = windowEndsAt;
            this.count = initialCount;
        }
    }
}
