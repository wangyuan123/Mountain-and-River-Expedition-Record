package com.wargame.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NameChangePolicyTest {
    @Test
    void resetsAtBeijingMidnightRegardlessOfServerTimezone() {
        long renamedAt = Instant.parse("2026-09-29T15:59:59Z").toEpochMilli();
        long midnight = Instant.parse("2026-09-29T16:00:00Z").toEpochMilli();

        assertEquals(midnight, NameChangePolicy.nextAllowedAt(renamedAt, midnight - 1));
        assertEquals(0L, NameChangePolicy.nextAllowedAt(renamedAt, midnight));
        assertEquals(0L, NameChangePolicy.nextAllowedAt(0L, midnight - 1));
    }
}
