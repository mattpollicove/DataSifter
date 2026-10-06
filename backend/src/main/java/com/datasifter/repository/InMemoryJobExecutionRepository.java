package com.datasifter.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.datasifter.model.JobExecution;
import com.datasifter.support.JsonFileStore;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("json")
public class InMemoryJobExecutionRepository implements JobExecutionRepository {
    private final Path file;
    private final Map<String, JobExecution> executions = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryJobExecutionRepository() {
        this(JsonFileStore.resolveDataDirectory().resolve("job-executions.json"));
    }

    InMemoryJobExecutionRepository(Path file) {
        this.file = file;
        boolean migratedLegacyRunningJob = false;
        for (JobExecution execution : JsonFileStore.readList(file, new TypeReference<List<JobExecution>>() {})) {
            if ("running".equalsIgnoreCase(execution.getStatus()) && execution.getLeaseExpiresAt() == null) {
                execution.setStatus("queued");
                execution.setWorkerId(null);
                execution.setNextAttemptAt(Instant.now());
                execution.setLastError("Recovered job without a worker lease");
                migratedLegacyRunningJob = true;
            }
            executions.put(execution.getId(), execution);
        }
        if (migratedLegacyRunningJob) {
            persist();
        }
    }

    @Override
    public synchronized List<JobExecution> findAll() {
        return new ArrayList<>(executions.values());
    }

    @Override
    public synchronized Optional<JobExecution> findById(String id) {
        return Optional.ofNullable(executions.get(id));
    }

    @Override
    public synchronized JobExecution save(JobExecution execution) {
        if (execution.getId() == null || execution.getId().isBlank()) {
            execution.setId(UUID.randomUUID().toString());
        }
        if (execution.getCreatedAt() == null) {
            execution.setCreatedAt(java.time.Instant.now());
        }
        execution.setUpdatedAt(java.time.Instant.now());
        executions.put(execution.getId(), execution);
        persist();
        return execution;
    }

    @Override
    public synchronized void deleteById(String id) {
        executions.remove(id);
        persist();
    }

    @Override
    public synchronized Optional<JobExecution> claimNext(String workerId, Instant now, Instant leaseExpiresAt) {
        return executions.values().stream()
                .filter(execution -> "queued".equalsIgnoreCase(execution.getStatus()))
                .filter(execution -> execution.getNextAttemptAt() == null || !execution.getNextAttemptAt().isAfter(now))
                .min(java.util.Comparator.comparing(
                        JobExecution::getNextAttemptAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(
                                JobExecution::getCreatedAt,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(JobExecution::getId))
                .map(execution -> {
                    execution.setStatus("running");
                    execution.setWorkerId(workerId);
                    execution.setHeartbeatAt(now);
                    execution.setLeaseExpiresAt(leaseExpiresAt);
                    execution.setUpdatedAt(now);
                    persist();
                    return execution;
                });
    }

    @Override
    public synchronized boolean heartbeat(
            String id, String workerId, Instant now, Instant leaseExpiresAt, int progress, int success, int failed) {
        JobExecution execution = executions.get(id);
        if (!ownsActiveLease(execution, workerId, now)) {
            return false;
        }
        execution.setProgress(Math.max(0, Math.min(100, progress)));
        execution.setSuccess(Math.max(0, success));
        execution.setFailed(Math.max(0, failed));
        execution.setHeartbeatAt(now);
        execution.setLeaseExpiresAt(leaseExpiresAt);
        execution.setUpdatedAt(now);
        persist();
        return true;
    }

    @Override
    public synchronized boolean complete(String id, String workerId, Instant now, int success, int failed) {
        JobExecution execution = executions.get(id);
        if (!ownsActiveLease(execution, workerId, now)) {
            return false;
        }
        execution.setStatus("success");
        execution.setProgress(100);
        execution.setSuccess(Math.max(0, success));
        execution.setFailed(Math.max(0, failed));
        clearLease(execution);
        execution.setUpdatedAt(now);
        persist();
        return true;
    }

    @Override
    public synchronized boolean fail(String id, String workerId, Instant now, int maxRetries, Instant nextAttemptAt) {
        JobExecution execution = executions.get(id);
        if (!ownsActiveLease(execution, workerId, now)) {
            return false;
        }
        applyFailure(execution, now, maxRetries, nextAttemptAt);
        persist();
        return true;
    }

    @Override
    public synchronized int recoverExpiredLeases(
            Instant now, int maxRetries, Duration retryBaseDelay, Duration retryMaxDelay) {
        int recovered = 0;
        for (JobExecution execution : executions.values()) {
            if ("running".equalsIgnoreCase(execution.getStatus())
                    && execution.getLeaseExpiresAt() != null
                    && !execution.getLeaseExpiresAt().isAfter(now)) {
                int retryCount = execution.getRetryCount() == null ? 0 : execution.getRetryCount();
                Instant nextAttemptAt = nextRetryAt(retryCount + 1, now, retryBaseDelay, retryMaxDelay);
                applyFailure(execution, now, maxRetries, nextAttemptAt);
                recovered++;
            }
        }
        if (recovered > 0) {
            persist();
        }
        return recovered;
    }

    private void persist() {
        JsonFileStore.writeList(file, new ArrayList<>(executions.values()));
    }

    private boolean ownsActiveLease(JobExecution execution, String workerId, Instant now) {
        return execution != null
                && "running".equalsIgnoreCase(execution.getStatus())
                && workerId != null
                && workerId.equals(execution.getWorkerId())
                && execution.getLeaseExpiresAt() != null
                && execution.getLeaseExpiresAt().isAfter(now);
    }

    private void applyFailure(JobExecution execution, Instant now, int maxRetries, Instant nextAttemptAt) {
        int retryCount = (execution.getRetryCount() == null ? 0 : execution.getRetryCount()) + 1;
        execution.setRetryCount(retryCount);
        execution.setLastError("Worker execution failed or its lease expired");
        execution.setStatus(retryCount <= maxRetries ? "queued" : "failed");
        execution.setNextAttemptAt(retryCount <= maxRetries ? nextAttemptAt : null);
        clearLease(execution);
        execution.setUpdatedAt(now);
    }

    private void clearLease(JobExecution execution) {
        execution.setWorkerId(null);
        execution.setLeaseExpiresAt(null);
    }

    private Instant nextRetryAt(int retryCount, Instant now, Duration baseDelay, Duration maxDelay) {
        int exponent = Math.min(Math.max(retryCount - 1, 0), 30);
        Duration delay;
        try {
            delay = baseDelay.multipliedBy(1L << exponent);
        } catch (ArithmeticException overflow) {
            delay = maxDelay;
        }
        return now.plus(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
    }
}
