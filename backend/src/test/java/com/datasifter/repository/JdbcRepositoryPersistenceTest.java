package com.datasifter.repository;

import com.datasifter.model.AuditEntry;
import com.datasifter.model.Connector;
import com.datasifter.model.FieldMapping;
import com.datasifter.model.JobExecution;
import com.datasifter.model.Workflow;
import com.datasifter.model.WorkflowCanvasNode;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class JdbcRepositoryPersistenceTest {
    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ConnectorRepository connectorRepository;

    @Autowired
    private JobExecutionRepository jobExecutionRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void repositoriesPersistUpdatesAndDeletesThroughMigratedDatabaseSchema() {
        String suffix = UUID.randomUUID().toString();
        String workflowId = suffix;
        Workflow workflow = new Workflow(workflowId, "Initial", "Ops", "draft", "manual");
        WorkflowCanvasNode node = new WorkflowCanvasNode();
        node.setId("source");
        node.setType("source");
        node.setName("CSV source");
        node.setX(24);
        node.setY(48);
        workflow.setCanvasNodes(java.util.List.of(node));
        FieldMapping mapping = new FieldMapping();
        mapping.setSourceField("email");
        mapping.setTargetField("email_hash");
        mapping.setPrivacyAction("sha256");
        mapping.setTransformations(java.util.List.of("trim", "lowercase"));
        workflow.setSourceType("ldaps");
        workflow.setSourceLdapUrl("ldaps://directory.example.com:636");
        workflow.setSourceLdapBaseDn("ou=people,dc=example,dc=com");
        workflow.setSourceLdapAttributes(java.util.List.of("mail", "cn"));
        workflow.setFieldMappings(java.util.List.of(mapping));
        workflowRepository.save(workflow);
        workflow.setName("Updated");
        workflowRepository.save(workflow);
        Workflow persistedWorkflow = workflowRepository.findById(workflowId).orElseThrow();
        assertEquals("Updated", persistedWorkflow.getName());
        assertEquals("source", persistedWorkflow.getCanvasNodes().get(0).getId());
        assertEquals("ldaps", persistedWorkflow.getSourceType());
        assertEquals(java.util.List.of("mail", "cn"), persistedWorkflow.getSourceLdapAttributes());
        assertEquals("sha256", persistedWorkflow.getFieldMappings().get(0).getPrivacyAction());
        assertEquals(java.util.List.of("trim", "lowercase"),
                persistedWorkflow.getFieldMappings().get(0).getTransformations());
        workflowRepository.deleteById(workflowId);
        assertTrue(workflowRepository.findById(workflowId).isEmpty());

        String connectorId = suffix;
        Connector connector = new Connector(connectorId, "Source", "JDBC", "online", "JDBC", "now", "TLS");
        connectorRepository.save(connector);
        assertEquals("Source", connectorRepository.findById(connectorId).orElseThrow().getName());
        connectorRepository.deleteById(connectorId);

        String jobId = suffix;
        jobExecutionRepository.save(new JobExecution(jobId, "Import", workflowId, "queued", 0, 0, 0));
        JobExecution job = jobExecutionRepository.findById(jobId).orElseThrow();
        job.setStatus("running");
        jobExecutionRepository.save(job);
        assertEquals("running", jobExecutionRepository.findById(jobId).orElseThrow().getStatus());
        jobExecutionRepository.deleteById(jobId);

        String auditId = suffix;
        auditRepository.save(new AuditEntry(auditId, Instant.now(), "operator", "Workflow updated", workflowId));
        assertTrue(auditRepository.findAll().stream().anyMatch(entry -> auditId.equals(entry.getId())));
        auditRepository.clear();
        assertTrue(auditRepository.findAll().isEmpty());
    }

    @Test
    void databaseQueueAllowsOnlyOneConcurrentWorkerClaim() throws Exception {
        String id = UUID.randomUUID().toString();
        JobExecution queued = new JobExecution(id, "Claim test", "wf-test", "queued", 0, 0, 0);
        queued.setNextAttemptAt(Instant.now().minusSeconds(1));
        jobExecutionRepository.save(queued);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<JobExecution>> first = workers.submit(() -> {
                start.await();
                Instant now = Instant.now();
                return jobExecutionRepository.claimNext("worker-a", now, now.plusSeconds(60));
            });
            Future<Optional<JobExecution>> second = workers.submit(() -> {
                start.await();
                Instant now = Instant.now();
                return jobExecutionRepository.claimNext("worker-b", now, now.plusSeconds(60));
            });

            start.countDown();
            Optional<JobExecution> firstClaim = first.get();
            Optional<JobExecution> secondClaim = second.get();
            assertEquals(1, (firstClaim.isPresent() ? 1 : 0) + (secondClaim.isPresent() ? 1 : 0));

            JobExecution claimed = jobExecutionRepository.findById(id).orElseThrow();
            assertEquals("running", claimed.getStatus());
            assertTrue(claimed.getWorkerId().equals("worker-a") || claimed.getWorkerId().equals("worker-b"));
            assertTrue(jobExecutionRepository.heartbeat(
                    id, claimed.getWorkerId(), Instant.now(), Instant.now().plusSeconds(60), 50, 4, 0));
            assertTrue(jobExecutionRepository.complete(id, claimed.getWorkerId(), Instant.now(), 8, 0));
            assertEquals("success", jobExecutionRepository.findById(id).orElseThrow().getStatus());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void databaseQueueRecoversExpiredLeasesWithRetryLimit() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        JobExecution queued = new JobExecution(id, "Recovery test", "wf-test", "queued", 0, 0, 0);
        queued.setNextAttemptAt(now.minusSeconds(1));
        jobExecutionRepository.save(queued);

        Instant leaseExpiry = now.plusSeconds(60);
        JobExecution claimed = jobExecutionRepository.claimNext("worker-a", now, leaseExpiry).orElseThrow();
        assertEquals(id, claimed.getId(), () -> "Claimed unexpected queued row " + claimed.getId());
        Instant recoveryTime = leaseExpiry.plusSeconds(1);

        Integer expiredRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_executions WHERE id = ? AND queue_status = 'running' "
                        + "AND queue_lease_expires_at <= ? AND queue_retry_count = 0",
                Integer.class,
                id,
                java.sql.Timestamp.from(recoveryTime));
        assertEquals(1, expiredRows, () -> "Queue row: "
                + jdbcTemplate.queryForMap(
                        "SELECT queue_status, queue_retry_count, queue_lease_expires_at "
                                + "FROM job_executions WHERE id = ?", id));
        assertEquals("running", jobExecutionRepository.findById(id).orElseThrow().getStatus());
        assertEquals(0, jobExecutionRepository.findById(id).orElseThrow().getRetryCount());
        int recoveredCount = jobExecutionRepository.recoverExpiredLeases(
                recoveryTime, 0, Duration.ofSeconds(5), Duration.ofMinutes(5));
        assertEquals(1, recoveredCount, () -> "Queue row after recovery: "
                + jdbcTemplate.queryForMap(
                        "SELECT queue_status, queue_retry_count, queue_lease_expires_at "
                                + "FROM job_executions WHERE id = ?", id));
        JobExecution recovered = jobExecutionRepository.findById(id).orElseThrow();
        assertEquals("failed", recovered.getStatus());
        assertEquals(1, recovered.getRetryCount());
        assertTrue(recovered.getWorkerId() == null);
    }
}
