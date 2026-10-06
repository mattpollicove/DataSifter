package com.datasifter.repository;

import com.datasifter.model.JobExecution;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobExecutionRepository {
    List<JobExecution> findAll();
    Optional<JobExecution> findById(String id);
    JobExecution save(JobExecution execution);
    Optional<JobExecution> claimNext(String workerId, Instant now, Instant leaseExpiresAt);
    boolean heartbeat(
            String id, String workerId, Instant now, Instant leaseExpiresAt, int progress, int success, int failed);
    boolean complete(String id, String workerId, Instant now, int success, int failed);
    boolean fail(String id, String workerId, Instant now, int maxRetries, Instant nextAttemptAt);
    int recoverExpiredLeases(Instant now, int maxRetries, Duration retryBaseDelay, Duration retryMaxDelay);
    void deleteById(String id);
}
