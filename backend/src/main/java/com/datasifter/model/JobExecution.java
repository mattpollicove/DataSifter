package com.datasifter.model;

import java.time.Instant;
import java.util.Objects;

public class JobExecution {
    private String id;
    private String name;
    private String workflowId;
    private String status;
    private Integer progress;
    private Integer success;
    private Integer failed;
    private Integer retryCount;
    private String lastError;
    private String workerId;
    private Instant nextAttemptAt;
    private Instant heartbeatAt;
    private Instant leaseExpiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    public JobExecution() {
    }

    public JobExecution(String id, String name, String workflowId, String status, Integer progress, Integer success, Integer failed) {
        this.id = id;
        this.name = name;
        this.workflowId = workflowId;
        this.status = status;
        this.progress = progress;
        this.success = success;
        this.failed = failed;
        this.retryCount = 0;
        this.lastError = null;
        this.workerId = null;
        this.nextAttemptAt = Instant.now();
        this.heartbeatAt = null;
        this.leaseExpiresAt = null;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public Integer getSuccess() { return success; }
    public void setSuccess(Integer success) { this.success = success; }

    public Integer getFailed() { return failed; }
    public void setFailed(Integer failed) { this.failed = failed; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public Instant getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(Instant heartbeatAt) { this.heartbeatAt = heartbeatAt; }

    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof JobExecution execution)) return false;
        return Objects.equals(id, execution.id)
                && Objects.equals(name, execution.name)
                && Objects.equals(workflowId, execution.workflowId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, workflowId);
    }
}
