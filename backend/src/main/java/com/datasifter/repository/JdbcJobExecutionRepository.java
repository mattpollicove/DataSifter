package com.datasifter.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.datasifter.model.JobExecution;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!json")
public class JdbcJobExecutionRepository implements JobExecutionRepository {
    private final JdbcJsonTable<JobExecution> table;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public JdbcJobExecutionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = new JdbcJsonTable<>(jdbcTemplate, "job_executions", JobExecution.class);
        initializeQueueColumns();
    }

    @Override
    public List<JobExecution> findAll() {
        return table.findAll();
    }

    @Override
    public Optional<JobExecution> findById(String id) {
        return table.findById(id);
    }

    @Override
    public JobExecution save(JobExecution execution) {
        if (execution.getId() == null || execution.getId().isBlank()) {
            execution.setId(UUID.randomUUID().toString());
        }
        if (execution.getCreatedAt() == null) {
            execution.setCreatedAt(Instant.now());
        }
        execution.setUpdatedAt(Instant.now());
        String serialized = serialize(execution);
        int updated = jdbcTemplate.update(
                "UPDATE job_executions SET payload_json = ?, queue_status = ?, queue_next_attempt_at = ?, "
                        + "queue_worker_id = ?, queue_heartbeat_at = ?, queue_lease_expires_at = ?, queue_retry_count = ? "
                        + "WHERE id = ?",
                serialized,
                execution.getStatus(),
                timestamp(execution.getNextAttemptAt()),
                execution.getWorkerId(),
                timestamp(execution.getHeartbeatAt()),
                timestamp(execution.getLeaseExpiresAt()),
                execution.getRetryCount() == null ? 0 : execution.getRetryCount(),
                execution.getId());
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO job_executions "
                            + "(id, payload_json, queue_status, queue_next_attempt_at, queue_worker_id, "
                            + "queue_heartbeat_at, queue_lease_expires_at, queue_retry_count) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    execution.getId(),
                    serialized,
                    execution.getStatus(),
                    timestamp(execution.getNextAttemptAt()),
                    execution.getWorkerId(),
                    timestamp(execution.getHeartbeatAt()),
                    timestamp(execution.getLeaseExpiresAt()),
                    execution.getRetryCount() == null ? 0 : execution.getRetryCount());
        }
        return execution;
    }

    @Override
    @Transactional
    public Optional<JobExecution> claimNext(String workerId, Instant now, Instant leaseExpiresAt) {
        List<String> candidates = jdbcTemplate.query(
                "SELECT id FROM job_executions WHERE queue_status = 'queued' "
                        + "AND (queue_next_attempt_at IS NULL OR queue_next_attempt_at <= ?) "
                        + "ORDER BY queue_next_attempt_at, id LIMIT 32",
                (resultSet, rowNumber) -> resultSet.getString("id"),
                timestamp(now));
        for (String id : candidates) {
            Optional<JobExecution> candidate = table.findById(id);
            if (candidate.isEmpty()) {
                continue;
            }
            int claimed = jdbcTemplate.update(
                    "UPDATE job_executions SET queue_status = 'running', queue_worker_id = ?, "
                            + "queue_heartbeat_at = ?, queue_lease_expires_at = ? "
                            + "WHERE id = ? AND queue_status = 'queued' "
                            + "AND (queue_next_attempt_at IS NULL OR queue_next_attempt_at <= ?)",
                    workerId,
                    timestamp(now),
                    timestamp(leaseExpiresAt),
                    id,
                    timestamp(now));
            if (claimed == 1) {
                JobExecution execution = candidate.get();
                execution.setStatus("running");
                execution.setWorkerId(workerId);
                execution.setHeartbeatAt(now);
                execution.setLeaseExpiresAt(leaseExpiresAt);
                save(execution);
                return Optional.of(execution);
            }
        }
        return Optional.empty();
    }

    @Override
    @Transactional
    public boolean heartbeat(
            String id, String workerId, Instant now, Instant leaseExpiresAt, int progress, int success, int failed) {
        Optional<JobExecution> candidate = table.findById(id);
        if (candidate.isEmpty() || !isActiveOwner(candidate.get(), workerId, now)) {
            return false;
        }
        int updated = jdbcTemplate.update(
                "UPDATE job_executions SET queue_heartbeat_at = ?, queue_lease_expires_at = ? "
                        + "WHERE id = ? AND queue_status = 'running' AND queue_worker_id = ? "
                        + "AND queue_lease_expires_at > ?",
                timestamp(now), timestamp(leaseExpiresAt), id, workerId, timestamp(now));
        if (updated != 1) {
            return false;
        }
        JobExecution execution = candidate.get();
        execution.setProgress(Math.max(0, Math.min(100, progress)));
        execution.setSuccess(Math.max(0, success));
        execution.setFailed(Math.max(0, failed));
        execution.setHeartbeatAt(now);
        execution.setLeaseExpiresAt(leaseExpiresAt);
        save(execution);
        return true;
    }

    @Override
    @Transactional
    public boolean complete(String id, String workerId, Instant now, int success, int failed) {
        Optional<JobExecution> candidate = table.findById(id);
        if (candidate.isEmpty() || !isActiveOwner(candidate.get(), workerId, now)) {
            return false;
        }
        int updated = jdbcTemplate.update(
                "UPDATE job_executions SET queue_status = 'success', queue_worker_id = NULL, "
                        + "queue_lease_expires_at = NULL WHERE id = ? AND queue_status = 'running' "
                        + "AND queue_worker_id = ? AND queue_lease_expires_at > ?",
                id, workerId, timestamp(now));
        if (updated != 1) {
            return false;
        }
        JobExecution execution = candidate.get();
        execution.setStatus("success");
        execution.setProgress(100);
        execution.setSuccess(Math.max(0, success));
        execution.setFailed(Math.max(0, failed));
        clearLease(execution);
        save(execution);
        return true;
    }

    @Override
    @Transactional
    public boolean fail(String id, String workerId, Instant now, int maxRetries, Instant nextAttemptAt) {
        Optional<JobExecution> candidate = table.findById(id);
        if (candidate.isEmpty() || !isActiveOwner(candidate.get(), workerId, now)) {
            return false;
        }
        JobExecution execution = candidate.get();
        int retryCount = (execution.getRetryCount() == null ? 0 : execution.getRetryCount()) + 1;
        boolean retry = retryCount <= maxRetries;
        String status = retry ? "queued" : "failed";
        int updated = jdbcTemplate.update(
                "UPDATE job_executions SET queue_status = ?, queue_next_attempt_at = ?, queue_worker_id = NULL, "
                        + "queue_lease_expires_at = NULL, queue_retry_count = ? "
                        + "WHERE id = ? AND queue_status = 'running' AND queue_worker_id = ? "
                        + "AND queue_lease_expires_at > ? AND queue_retry_count = ?",
                status, retry ? timestamp(nextAttemptAt) : null, retryCount,
                id, workerId, timestamp(now), execution.getRetryCount() == null ? 0 : execution.getRetryCount());
        if (updated != 1) {
            return false;
        }
        applyFailure(execution, now, status, retryCount, retry ? nextAttemptAt : null);
        save(execution);
        return true;
    }

    @Override
    @Transactional
    public int recoverExpiredLeases(Instant now, int maxRetries, Duration retryBaseDelay, Duration retryMaxDelay) {
        List<String> expiredIds = jdbcTemplate.query(
                "SELECT id FROM job_executions WHERE queue_status = 'running' AND queue_lease_expires_at <= ?",
                (resultSet, rowNumber) -> resultSet.getString("id"),
                timestamp(now));
        int recovered = 0;
        for (String id : expiredIds) {
            Optional<JobExecution> candidate = table.findById(id);
            if (candidate.isEmpty()) {
                continue;
            }
            JobExecution execution = candidate.get();
            int previousRetryCount = execution.getRetryCount() == null ? 0 : execution.getRetryCount();
            int retryCount = previousRetryCount + 1;
            boolean retry = retryCount <= maxRetries;
            Instant nextAttemptAt = retry ? nextRetryAt(retryCount, now, retryBaseDelay, retryMaxDelay) : null;
            String status = retry ? "queued" : "failed";
            int updated = jdbcTemplate.update(
                    "UPDATE job_executions SET queue_status = ?, queue_next_attempt_at = ?, queue_worker_id = NULL, "
                            + "queue_lease_expires_at = NULL, queue_retry_count = ? "
                            + "WHERE id = ? AND queue_status = 'running' AND queue_lease_expires_at <= ? "
                            + "AND queue_retry_count = ?",
                    status, timestamp(nextAttemptAt), retryCount, id, timestamp(now), previousRetryCount);
            if (updated == 1) {
                applyFailure(execution, now, status, retryCount, nextAttemptAt);
                save(execution);
                recovered++;
            }
        }
        return recovered;
    }

    @Override
    public void deleteById(String id) {
        table.deleteById(id);
    }

    private void initializeQueueColumns() {
        List<String> uninitializedIds = jdbcTemplate.query(
                "SELECT id FROM job_executions WHERE queue_status IS NULL",
                (resultSet, rowNumber) -> resultSet.getString("id"));
        for (String id : uninitializedIds) {
            table.findById(id).ifPresent(execution -> {
                if ("running".equalsIgnoreCase(execution.getStatus()) && execution.getLeaseExpiresAt() == null) {
                    execution.setStatus("queued");
                    execution.setWorkerId(null);
                    execution.setNextAttemptAt(Instant.now());
                    execution.setLastError("Recovered job without a worker lease");
                }
                int updated = jdbcTemplate.update(
                        "UPDATE job_executions SET queue_status = ?, queue_next_attempt_at = ?, "
                                + "queue_worker_id = ?, queue_heartbeat_at = ?, queue_lease_expires_at = ?, "
                                + "queue_retry_count = ? WHERE id = ? AND queue_status IS NULL",
                        execution.getStatus(),
                        timestamp(execution.getNextAttemptAt()),
                        execution.getWorkerId(),
                        timestamp(execution.getHeartbeatAt()),
                        timestamp(execution.getLeaseExpiresAt()),
                        execution.getRetryCount() == null ? 0 : execution.getRetryCount(),
                        id);
                if (updated == 1) {
                    save(execution);
                }
            });
        }
    }

    private boolean isActiveOwner(JobExecution execution, String workerId, Instant now) {
        return "running".equalsIgnoreCase(execution.getStatus())
                && workerId != null
                && workerId.equals(execution.getWorkerId())
                && execution.getLeaseExpiresAt() != null
                && execution.getLeaseExpiresAt().isAfter(now);
    }

    private void applyFailure(JobExecution execution, Instant now, String status, int retryCount, Instant nextAttemptAt) {
        execution.setStatus(status);
        execution.setRetryCount(retryCount);
        execution.setLastError("Worker execution failed or its lease expired");
        execution.setNextAttemptAt(nextAttemptAt);
        clearLease(execution);
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

    private void clearLease(JobExecution execution) {
        execution.setWorkerId(null);
        execution.setLeaseExpiresAt(null);
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String serialize(JobExecution execution) {
        try {
            return objectMapper.writeValueAsString(execution);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize JobExecution for database storage", e);
        }
    }
}
