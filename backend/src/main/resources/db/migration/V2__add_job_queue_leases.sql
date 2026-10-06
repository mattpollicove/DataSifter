ALTER TABLE job_executions
    ADD COLUMN queue_status VARCHAR(20);

ALTER TABLE job_executions
    ADD COLUMN queue_next_attempt_at TIMESTAMP(6) NULL;

ALTER TABLE job_executions
    ADD COLUMN queue_worker_id VARCHAR(255);

ALTER TABLE job_executions
    ADD COLUMN queue_heartbeat_at TIMESTAMP(6) NULL;

ALTER TABLE job_executions
    ADD COLUMN queue_lease_expires_at TIMESTAMP(6) NULL;

ALTER TABLE job_executions
    ADD COLUMN queue_retry_count INTEGER;

CREATE INDEX idx_job_executions_queue
    ON job_executions (queue_status, queue_next_attempt_at, queue_lease_expires_at);
