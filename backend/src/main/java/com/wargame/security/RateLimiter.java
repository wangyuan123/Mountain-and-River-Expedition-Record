package com.wargame.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简易内存版限流器。
 * 生产环境应当换成 Redis (Bucket4j / Redisson)，否则重启失效且不支持多实例。
 * 现阶段为单实例 + 防暴力枚举已足够。
 */
@Component
public class RateLimiter {

    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    /** True if allowed, false if exceeded. Each window is independent. */
    public boolean allow(String key, int maxPerWindow, long windowMs) {
        long now = System.currentTimeMillis();
        Counter c = counters.computeIfAbsent(key, k -> new Counter());
        synchronized (c) {
            if (now - c.windowStart >= windowMs) {
                c.windowStart = now;
                c.count.set(0);
            }
            return c.count.incrementAndGet() <= maxPerWindow;
        }
    }

    /** Returns remaining seconds until the window resets (best-effort). */
    public long retryAfterSeconds(String key, long windowMs) {
        Counter c = counters.get(key);
        if (c == null) return 0;
        long elapsed = System.currentTimeMillis() - c.windowStart;
        if (elapsed >= windowMs) return 0;
        return Math.max(1, (windowMs - elapsed + 999) / 1000);
    }

    private static class Counter {
        long windowStart = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger(0);
    }
}
