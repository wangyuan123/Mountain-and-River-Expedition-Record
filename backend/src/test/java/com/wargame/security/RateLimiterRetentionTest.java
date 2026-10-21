package com.wargame.security;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterRetentionTest {
    @Test void capacityNeverEvictsLiveQuotaAndExpiredWindowsReleaseCapacity() {
        AtomicLong now = new AtomicLong(1000);
        RateLimiter limiter = new RateLimiter(2, now::get);
        assertTrue(limiter.allow("a", 1, 100));
        assertTrue(limiter.allow("b", 1, 100));
        assertFalse(limiter.allow("c", 1, 100));
        assertFalse(limiter.allow("a", 1, 100));
        assertEquals(1, limiter.capacityRejectionCount());
        now.addAndGet(100);
        assertTrue(limiter.allow("c", 1, 100));
        assertEquals(2, limiter.evictedWindowCount());
        assertEquals(1, limiter.keyCount());
    }

    @Test void concurrentAdmissionAndEvictionKeepExactlyOneSharedQuota() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        RateLimiter limiter = new RateLimiter(20, now::get);
        var pool = Executors.newFixedThreadPool(8);
        try {
            for (int window = 0; window < 3; window++) {
                var work = new ArrayList<Callable<Boolean>>();
                for (int i = 0; i < 100; i++) work.add(() -> {
                    limiter.evictExpiredWindows();
                    return limiter.allow("shared", 7, 100);
                });
                int allowed = 0;
                for (Future<Boolean> result : pool.invokeAll(work)) if (result.get()) allowed++;
                assertEquals(7, allowed);
                now.addAndGet(100);
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentNewKeysNeverExceedCapacity() throws Exception {
        RateLimiter limiter = new RateLimiter(10, () -> 1000);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var work = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 100; i++) { String key = "key-" + i; work.add(() -> limiter.allow(key, 1, 100)); }
            int allowed = 0;
            for (Future<Boolean> result : pool.invokeAll(work)) if (result.get()) allowed++;
            assertEquals(10, allowed);
            assertEquals(10, limiter.keyCount());
            assertEquals(90, limiter.capacityRejectionCount());
        } finally { pool.shutdownNow(); }
    }
}
