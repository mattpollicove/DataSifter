package com.datasifter.service;

import com.datasifter.config.JobQueueProperties;
import com.datasifter.model.JobExecution;
import com.datasifter.repository.JobExecutionRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobExecutionService {
    private static final List<String> ALLOWED_STATUSES = List.of("queued", "running", "paused", "failed", "success", "cancelled");
    private final JobExecutionRepository repository;
    private final JobQueueProperties queueProperties;

    public JobExecutionService(JobExecutionRepository repository, JobQueueProperties queueProperties) {
        this.repository = repository;
        this.queueProperties = queueProperties;
    }

    public List<JobExecution> getAllExecutions() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(JobExecution::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public Optional<JobExecution> getExecutionById(String id) {
        return repository.findById(id);
    }

    public JobExecution queueJob(String name, String workflowId) {
        JobExecution execution = new JobExecution(
                java.util.UUID.randomUUID().toString(),
                name,
                workflowId,
                "queued",
                0,
                0,
                0
        );
        return repository.save(execution);
    }

    public JobExecution updateProgress(String id, String status, int progress, int success, int failed) {
        JobExecution execution = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + id));

        String normalizedStatus = normalizeStatus(status, execution.getStatus());
        if (!normalizedStatus.equalsIgnoreCase(execution.getStatus())
                || "running".equalsIgnoreCase(normalizedStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Job state transitions must use the worker or lifecycle endpoints");
        }
        int normalizedProgress = clamp(progress, 0, 100);
        int normalizedSuccess = Math.max(0, success);
        int normalizedFailed = Math.max(0, failed);

        execution.setStatus(normalizedStatus);
        execution.setProgress(normalizedProgress);
        execution.setSuccess(normalizedSuccess);
        execution.setFailed(normalizedFailed);
        return repository.save(execution);
    }

    public JobExecution pauseExecution(String id) {
        JobExecution execution = findExecution(id);
        requireNonTerminal(execution);
        execution.setStatus("paused");
        clearLease(execution);
        return repository.save(execution);
    }

    public JobExecution resumeExecution(String id) {
        JobExecution execution = findExecution(id);
        if (!"paused".equalsIgnoreCase(execution.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only paused jobs can be resumed");
        }
        execution.setStatus("queued");
        execution.setNextAttemptAt(Instant.now());
        clearLease(execution);
        return repository.save(execution);
    }

    public JobExecution cancelExecution(String id) {
        JobExecution execution = findExecution(id);
        requireNonTerminal(execution);
        execution.setStatus("cancelled");
        clearLease(execution);
        return repository.save(execution);
    }

    public Optional<JobExecution> claimNext(String workerId) {
        validateWorkerId(workerId);
        Instant now = Instant.now();
        return repository.claimNext(workerId.trim(), now, now.plus(queueProperties.getWorkerLease()));
    }

    public JobExecution heartbeat(
            String id, String workerId, int progress, int success, int failed) {
        validateWorkerId(workerId);
        Instant now = Instant.now();
        boolean renewed = repository.heartbeat(
                id,
                workerId.trim(),
                now,
                now.plus(queueProperties.getWorkerLease()),
                progress,
                success,
                failed);
        if (!renewed) {
            requireExistingJob(id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Worker does not own an active lease for this job");
        }
        return findExecution(id);
    }

    public JobExecution complete(String id, String workerId, int success, int failed) {
        validateWorkerId(workerId);
        boolean completed = repository.complete(id, workerId.trim(), Instant.now(), success, failed);
        if (!completed) {
            requireExistingJob(id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Worker does not own an active lease for this job");
        }
        return findExecution(id);
    }

    public FailureResult fail(String id, String workerId) {
        return fail(id, workerId, null);
    }

    public FailureResult fail(String id, String workerId, String error) {
        validateWorkerId(workerId);
        Instant now = Instant.now();
        JobExecution execution = findExecution(id);
        int retriesSoFar = execution.getRetryCount() == null ? 0 : execution.getRetryCount();
        Instant nextAttemptAt = queueProperties.nextRetryAt(retriesSoFar + 1, now);
        boolean failed = repository.fail(
                id,
                workerId.trim(),
                now,
                queueProperties.getMaxRetries(),
                nextAttemptAt);
        if (!failed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Worker does not own an active lease for this job");
        }
        JobExecution updated = findExecution(id);
        if (error != null && !error.isBlank()) {
            updated.setLastError(error.length() > 1000 ? error.substring(0, 1000) : error);
            updated = repository.save(updated);
        }
        return new FailureResult(updated, "queued".equals(updated.getStatus()));
    }

    public int recoverExpiredLeases() {
        return repository.recoverExpiredLeases(
                Instant.now(),
                queueProperties.getMaxRetries(),
                queueProperties.getRetryBaseDelay(),
                queueProperties.getRetryMaxDelay());
    }

    public List<WorkerHealth> getWorkerHealth() {
        Instant now = Instant.now();
        return repository.findAll().stream()
                .filter(execution -> "running".equalsIgnoreCase(execution.getStatus()))
                .filter(execution -> execution.getWorkerId() != null)
                .collect(Collectors.groupingBy(JobExecution::getWorkerId))
                .entrySet().stream()
                .map(entry -> {
                    Instant heartbeatAt = entry.getValue().stream()
                            .map(JobExecution::getHeartbeatAt)
                            .filter(value -> value != null)
                            .max(Comparator.naturalOrder())
                            .orElse(null);
                    Instant leaseExpiresAt = entry.getValue().stream()
                            .map(JobExecution::getLeaseExpiresAt)
                            .filter(value -> value != null)
                            .max(Comparator.naturalOrder())
                            .orElse(null);
                    String status = leaseExpiresAt != null && leaseExpiresAt.isAfter(now) ? "healthy" : "stale";
                    return new WorkerHealth(entry.getKey(), status, heartbeatAt, leaseExpiresAt, entry.getValue().size());
                })
                .sorted(Comparator.comparing(WorkerHealth::workerId))
                .toList();
    }

    public QueueSummary getQueueSummary() {
        List<JobExecution> executions = repository.findAll();
        int queued = 0;
        int running = 0;
        int paused = 0;
        int failed = 0;
        int success = 0;
        int cancelled = 0;

        for (JobExecution execution : executions) {
            String status = execution.getStatus() == null ? "queued" : execution.getStatus().toLowerCase();
            switch (status) {
                case "queued" -> queued++;
                case "running" -> running++;
                case "paused" -> paused++;
                case "failed" -> failed++;
                case "success" -> success++;
                case "cancelled" -> cancelled++;
                default -> queued++;
            }
        }

        return new QueueSummary(executions.size(), queued, running, paused, failed, success, cancelled);
    }

    public record QueueSummary(int total, int queued, int running, int paused, int failed, int succeeded, int cancelled) {}
    public record FailureResult(JobExecution execution, boolean retryScheduled) {}
    public record WorkerHealth(
            String workerId, String status, Instant lastHeartbeatAt, Instant leaseExpiresAt, int activeJobs) {}

    private JobExecution findExecution(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job execution not found"));
    }

    private void requireExistingJob(String id) {
        findExecution(id);
    }

    private void requireNonTerminal(JobExecution execution) {
        if (List.of("success", "failed", "cancelled").contains(execution.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Terminal jobs cannot be changed");
        }
    }

    private void validateWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.trim().length() > 255
                || workerId.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid worker ID is required");
        }
    }

    private void clearLease(JobExecution execution) {
        execution.setWorkerId(null);
        execution.setLeaseExpiresAt(null);
    }

    private String normalizeStatus(String status, String fallback) {
        if (status == null || status.isBlank()) {
            return fallback == null ? "queued" : fallback;
        }
        String normalized = status.trim().toLowerCase();
        if (ALLOWED_STATUSES.contains(normalized)) {
            return normalized;
        }
        return fallback == null ? "queued" : fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
