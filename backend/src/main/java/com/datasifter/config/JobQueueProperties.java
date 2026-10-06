package com.datasifter.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("datasifter.jobs")
public class JobQueueProperties {
    private int maxRetries = 3;
    private Duration retryBaseDelay = Duration.ofSeconds(5);
    private Duration retryMaxDelay = Duration.ofMinutes(5);
    private Duration workerLease = Duration.ofSeconds(60);
    private long recoveryIntervalMs = 5000;

    @PostConstruct
    public void validate() {
        if (maxRetries < 0
                || retryBaseDelay == null
                || retryBaseDelay.isNegative()
                || retryBaseDelay.isZero()
                || retryMaxDelay == null
                || retryMaxDelay.compareTo(retryBaseDelay) < 0
                || workerLease == null
                || workerLease.isNegative()
                || workerLease.isZero()
                || recoveryIntervalMs <= 0) {
            throw new IllegalStateException("Job queue retry and lease configuration is invalid");
        }
    }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public Duration getRetryBaseDelay() { return retryBaseDelay; }
    public void setRetryBaseDelay(Duration retryBaseDelay) { this.retryBaseDelay = retryBaseDelay; }

    public Duration getRetryMaxDelay() { return retryMaxDelay; }
    public void setRetryMaxDelay(Duration retryMaxDelay) { this.retryMaxDelay = retryMaxDelay; }

    public Duration getWorkerLease() { return workerLease; }
    public void setWorkerLease(Duration workerLease) { this.workerLease = workerLease; }

    public long getRecoveryIntervalMs() { return recoveryIntervalMs; }
    public void setRecoveryIntervalMs(long recoveryIntervalMs) { this.recoveryIntervalMs = recoveryIntervalMs; }

    public Instant nextRetryAt(int retryCount, Instant now) {
        int exponent = Math.min(Math.max(retryCount - 1, 0), 30);
        Duration delay;
        try {
            delay = retryBaseDelay.multipliedBy(1L << exponent);
        } catch (ArithmeticException overflow) {
            delay = retryMaxDelay;
        }
        return now.plus(delay.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : delay);
    }
}
