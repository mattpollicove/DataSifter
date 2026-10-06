package com.datasifter.config;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobQueuePropertiesTest {
    @Test
    void defaultsProvideThreeRetriesAndCappedExponentialBackoff() {
        JobQueueProperties properties = new JobQueueProperties();
        Instant now = Instant.parse("2026-10-06T00:00:00Z");

        assertEquals(3, properties.getMaxRetries());
        assertEquals(Duration.ofSeconds(60), properties.getWorkerLease());
        assertEquals(now.plusSeconds(5), properties.nextRetryAt(1, now));
        assertEquals(now.plusSeconds(10), properties.nextRetryAt(2, now));
        assertEquals(now.plus(Duration.ofMinutes(5)), properties.nextRetryAt(7, now));
    }
}
