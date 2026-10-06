package com.datasifter.repository;

import com.datasifter.model.AuditEntry;
import com.datasifter.model.Connector;
import com.datasifter.model.JobExecution;
import com.datasifter.model.Workflow;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsonRepositoryPersistenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void workflowCreateUpdateAndDeleteSurviveRepositoryRestarts() {
        Path file = temporaryDirectory.resolve("workflows.json");
        InMemoryWorkflowRepository repository = new InMemoryWorkflowRepository(file);
        Workflow workflow = new Workflow("wf-test", "Import", "Ops", "draft", "manual");
        repository.save(workflow);

        repository = new InMemoryWorkflowRepository(file);
        Workflow loaded = repository.findById("wf-test").orElseThrow();
        loaded.setName("Updated import");
        repository.save(loaded);

        repository = new InMemoryWorkflowRepository(file);
        assertEquals("Updated import", repository.findById("wf-test").orElseThrow().getName());
        repository.deleteById("wf-test");

        assertTrue(new InMemoryWorkflowRepository(file).findAll().isEmpty());
    }

    @Test
    void connectorAndJobStateSurviveRepositoryRestarts() {
        Path connectorFile = temporaryDirectory.resolve("connectors.json");
        InMemoryConnectorRepository connectorRepository = new InMemoryConnectorRepository(connectorFile);
        Connector connector = new Connector("connector-test", "Source", "JDBC", "online", "JDBC", "now", "TLS");
        connectorRepository.save(connector);
        connectorRepository = new InMemoryConnectorRepository(connectorFile);
        assertEquals("Source", connectorRepository.findById("connector-test").orElseThrow().getName());
        connectorRepository.deleteById("connector-test");
        assertTrue(new InMemoryConnectorRepository(connectorFile).findAll().isEmpty());

        Path jobFile = temporaryDirectory.resolve("jobs.json");
        InMemoryJobExecutionRepository jobRepository = new InMemoryJobExecutionRepository(jobFile);
        jobRepository.save(new JobExecution("job-test", "Import", "wf-test", "queued", 0, 0, 0));
        jobRepository = new InMemoryJobExecutionRepository(jobFile);
        JobExecution job = jobRepository.findById("job-test").orElseThrow();
        job.setStatus("running");
        job.setWorkerId("worker-test");
        job.setHeartbeatAt(Instant.now());
        job.setLeaseExpiresAt(Instant.now().plusSeconds(60));
        jobRepository.save(job);
        jobRepository = new InMemoryJobExecutionRepository(jobFile);
        assertEquals("running", jobRepository.findById("job-test").orElseThrow().getStatus());
        jobRepository.deleteById("job-test");
        assertTrue(new InMemoryJobExecutionRepository(jobFile).findAll().isEmpty());
    }

    @Test
    void auditEventsSurviveRepositoryRestartsAndClear() {
        Path file = temporaryDirectory.resolve("audit.json");
        InMemoryAuditRepository repository = new InMemoryAuditRepository(file);
        repository.save(new AuditEntry("event-test", Instant.now(), "operator", "Workflow created", "wf-test"));

        repository = new InMemoryAuditRepository(file);
        assertEquals(1, repository.findAll().size());
        repository.clear();

        assertTrue(new InMemoryAuditRepository(file).findAll().isEmpty());
    }

    @Test
    void concurrentWorkflowWritesAreNotLost() {
        Path file = temporaryDirectory.resolve("concurrent-workflows.json");
        InMemoryWorkflowRepository repository = new InMemoryWorkflowRepository(file);

        IntStream.range(0, 12).parallel().forEach(index ->
                repository.save(new Workflow("wf-" + index, "Workflow " + index, "Ops", "draft", "manual")));

        assertEquals(12, new InMemoryWorkflowRepository(file).findAll().size());
    }

    @Test
    void jsonQueueClaimsJobsAndRejectsNonOwningWorkers() {
        InMemoryJobExecutionRepository repository =
                new InMemoryJobExecutionRepository(temporaryDirectory.resolve("leases.json"));
        Instant now = Instant.now();
        JobExecution execution = new JobExecution("claim-test", "Import", "wf-test", "queued", 0, 0, 0);
        execution.setNextAttemptAt(now.minusSeconds(1));
        repository.save(execution);

        JobExecution claimed = repository.claimNext("worker-a", now, now.plusSeconds(60)).orElseThrow();

        assertEquals("running", claimed.getStatus());
        assertEquals("worker-a", claimed.getWorkerId());
        assertFalse(repository.claimNext("worker-b", now, now.plusSeconds(60)).isPresent());
        assertFalse(repository.complete("claim-test", "worker-b", now, 1, 0));
        assertTrue(repository.heartbeat(
                "claim-test", "worker-a", now.plusSeconds(10), now.plusSeconds(70), 50, 5, 0));
    }

    @Test
    void expiredJsonWorkerLeaseRetriesWithBackoffAndStopsAtRetryLimit() {
        InMemoryJobExecutionRepository repository =
                new InMemoryJobExecutionRepository(temporaryDirectory.resolve("recovery.json"));
        Instant now = Instant.now();
        JobExecution execution = new JobExecution("recovery-test", "Import", "wf-test", "queued", 0, 0, 0);
        execution.setNextAttemptAt(now);
        repository.save(execution);

        Instant firstLeaseExpiry = now.plusSeconds(60);
        repository.claimNext("worker-a", now, firstLeaseExpiry).orElseThrow();
        assertEquals(1, repository.recoverExpiredLeases(
                firstLeaseExpiry, 1, Duration.ofSeconds(5), Duration.ofMinutes(5)));

        JobExecution retried = repository.findById("recovery-test").orElseThrow();
        assertEquals("queued", retried.getStatus());
        assertEquals(1, retried.getRetryCount());
        assertEquals(firstLeaseExpiry.plusSeconds(5), retried.getNextAttemptAt());
        assertTrue(repository.claimNext(
                "worker-b", firstLeaseExpiry.plusSeconds(4), firstLeaseExpiry.plusSeconds(64)).isEmpty());

        Instant secondAttemptAt = retried.getNextAttemptAt();
        Instant secondLeaseExpiry = secondAttemptAt.plusSeconds(60);
        repository.claimNext("worker-b", secondAttemptAt, secondLeaseExpiry).orElseThrow();
        assertEquals(1, repository.recoverExpiredLeases(
                secondLeaseExpiry, 1, Duration.ofSeconds(5), Duration.ofMinutes(5)));
        assertEquals("failed", repository.findById("recovery-test").orElseThrow().getStatus());
        assertEquals(2, repository.findById("recovery-test").orElseThrow().getRetryCount());
    }

    @Test
    void legacyRunningJsonJobWithoutLeaseIsRequeuedAtStartup() {
        Path file = temporaryDirectory.resolve("legacy-running.json");
        InMemoryJobExecutionRepository initialRepository = new InMemoryJobExecutionRepository(file);
        initialRepository.save(new JobExecution("legacy-running", "Import", "wf-test", "running", 25, 2, 0));

        JobExecution recovered = new InMemoryJobExecutionRepository(file)
                .findById("legacy-running")
                .orElseThrow();

        assertEquals("queued", recovered.getStatus());
        assertTrue(recovered.getNextAttemptAt() != null);
        assertTrue(recovered.getLastError().contains("without a worker lease"));
    }
}
