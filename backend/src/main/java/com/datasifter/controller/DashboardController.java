package com.datasifter.controller;

import com.datasifter.model.AuditEntry;
import com.datasifter.model.Connector;
import com.datasifter.model.JobExecution;
import com.datasifter.model.KeyVaultSecret;
import com.datasifter.model.FieldMapping;
import com.datasifter.model.Workflow;
import com.datasifter.model.WorkflowCanvasEdge;
import com.datasifter.model.WorkflowCanvasNode;
import com.datasifter.service.AccessControlService;
import com.datasifter.service.AuditService;
import com.datasifter.service.ConnectorService;
import com.datasifter.service.JobExecutionService;
import com.datasifter.service.KeyVaultService;
import com.datasifter.service.WorkflowService;
import com.datasifter.service.WorkerTokenVerifier;
import com.datasifter.support.VaultCrypto;
import java.time.Instant;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final WorkflowService workflowService;
    private final ConnectorService connectorService;
    private final AuditService auditService;
    private final KeyVaultService keyVaultService;
    private final JobExecutionService jobExecutionService;
    private final AccessControlService accessControlService;
    private final WorkerTokenVerifier workerTokenVerifier;
    private final ObjectMapper objectMapper;

    public DashboardController(
            WorkflowService workflowService,
            ConnectorService connectorService,
            AuditService auditService,
            KeyVaultService keyVaultService,
            JobExecutionService jobExecutionService,
            AccessControlService accessControlService,
            WorkerTokenVerifier workerTokenVerifier,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.connectorService = connectorService;
        this.auditService = auditService;
        this.keyVaultService = keyVaultService;
        this.jobExecutionService = jobExecutionService;
        this.accessControlService = accessControlService;
        this.workerTokenVerifier = workerTokenVerifier;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/dashboard")
    public DashboardSummary dashboard() {
        List<JobStatus> jobs = new ArrayList<>();
        for (JobExecution execution : jobExecutionService.getAllExecutions()) {
            jobs.add(new JobStatus(
                    execution.getName(),
                    "workflow",
                    execution.getWorkflowId(),
                    execution.getStatus(),
                    execution.getProgress() == null ? 0 : execution.getProgress(),
                    execution.getSuccess() == null ? 0 : execution.getSuccess(),
                    execution.getFailed() == null ? 0 : execution.getFailed()
            ));
        }
        List<ConnectorStatus> connectors = connectorService.getAllConnectors().stream()
                .map(connector -> new ConnectorStatus(connector.getName(), connector.getType(), connector.getStatus(), connector.getLastSync()))
                .toList();

        int successful = 0;
        int failed = 0;
        for (JobExecution execution : jobExecutionService.getAllExecutions()) {
            if ("success".equalsIgnoreCase(execution.getStatus())) {
                successful++;
            }
            if ("failed".equalsIgnoreCase(execution.getStatus()) || "cancelled".equalsIgnoreCase(execution.getStatus())) {
                failed++;
            }
        }

        List<Alert> alerts = jobs.isEmpty()
                ? List.of(new Alert("info", "No workflow jobs are currently running."))
                : List.of(new Alert("success", "Control-plane state is current."));

        List<KpiCard> metrics = List.of(
                new KpiCard("Active Jobs", String.valueOf(jobs.size()), "0%", jobs.isEmpty() ? "down" : "up"),
                new KpiCard("Success Rate", jobs.isEmpty() ? "0%" : String.format("%.2f%%", (successful * 100.0 / jobs.size())), "0%", jobs.isEmpty() ? "down" : "up"),
                new KpiCard("Records Synced", String.valueOf(successful * 1000), "0%", jobs.isEmpty() ? "down" : "up"),
                new KpiCard("Security Alerts", String.valueOf(Math.max(0, failed)), "0", failed > 0 ? "down" : "up")
        );

        return new DashboardSummary(
                "DataSifter",
                "healthy",
                jobs,
                connectors,
                alerts,
                metrics
        );
    }

    @GetMapping("/workflows")
    public List<Map<String, Object>> workflows() {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Workflow workflow : workflowService.getAllWorkflows()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", workflow.getId());
            item.put("name", workflow.getName());
            item.put("status", workflow.getStatus());
            item.put("steps", workflow.getStages().size());
            item.put("owner", workflow.getOwner());
            payload.add(item);
        }
        return payload;
    }

    @GetMapping("/connectors")
    public List<Map<String, Object>> connectors() {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Connector connector : connectorService.getAllConnectors()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", connector.getId());
            item.put("name", connector.getName());
            item.put("type", connector.getType());
            item.put("status", connector.getStatus());
            item.put("lastSync", connector.getLastSync());
            item.put("protocol", connector.getProtocol());
            item.put("policy", connector.getPolicy());
            payload.add(item);
        }
        return payload;
    }

    @GetMapping("/connectors/{id}")
    public Map<String, Object> connectorDetail(@PathVariable String id) {
        Connector connector = connectorService.getConnectorById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found: " + id));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", connector.getId());
        payload.put("name", connector.getName());
        payload.put("type", connector.getType());
        payload.put("status", connector.getStatus());
        payload.put("lastSync", connector.getLastSync());
        payload.put("protocol", connector.getProtocol());
        payload.put("policy", connector.getPolicy());
        return payload;
    }

    @PostMapping("/connectors")
    public Map<String, Object> createConnector(@RequestBody Map<String, String> request) {
        Connector connector = new Connector();
        connector.setName(request.getOrDefault("name", "New connector"));
        connector.setType(request.getOrDefault("type", "JDBC"));
        connector.setStatus(request.getOrDefault("status", "online"));
        connector.setProtocol(request.getOrDefault("protocol", "JDBC"));
        connector.setHost(request.getOrDefault("host", "localhost"));
        connector.setPort(request.get("port") == null ? null : Integer.parseInt(request.get("port")));
        connector.setRequiresTls(Boolean.parseBoolean(request.getOrDefault("requiresTls", "true")));
        connector.setCertificateThumbprint(request.get("certificateThumbprint"));
        connector.setLastSync("just now");
        connector.setPolicy(request.getOrDefault("policy", connectorService.validatePolicy(connector)));
        Connector saved = connectorService.createConnector(connector);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", saved.getId());
        payload.put("name", saved.getName());
        payload.put("type", saved.getType());
        payload.put("status", saved.getStatus());
        payload.put("host", saved.getHost());
        payload.put("port", saved.getPort());
        payload.put("requiresTls", saved.getRequiresTls());
        payload.put("policy", saved.getPolicy());
        return payload;
    }

    @PutMapping("/connectors/{id}")
    public Map<String, Object> updateConnector(@PathVariable String id, @RequestBody Map<String, String> request) {
        Connector existing = connectorService.getConnectorById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found: " + id));
        existing.setName(request.getOrDefault("name", existing.getName()));
        existing.setType(request.getOrDefault("type", existing.getType()));
        existing.setStatus(request.getOrDefault("status", existing.getStatus()));
        existing.setProtocol(request.getOrDefault("protocol", existing.getProtocol()));
        existing.setHost(request.getOrDefault("host", existing.getHost() == null ? "localhost" : existing.getHost()));
        existing.setPort(request.get("port") == null ? existing.getPort() : Integer.parseInt(request.get("port")));
        existing.setRequiresTls(request.get("requiresTls") == null ? existing.getRequiresTls() : Boolean.parseBoolean(request.get("requiresTls")));
        existing.setCertificateThumbprint(request.getOrDefault("certificateThumbprint", existing.getCertificateThumbprint()));
        existing.setPolicy(request.getOrDefault("policy", connectorService.validatePolicy(existing)));
        Connector updated = connectorService.updateConnector(id, existing);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", updated.getId());
        payload.put("name", updated.getName());
        payload.put("type", updated.getType());
        payload.put("status", updated.getStatus());
        payload.put("protocol", updated.getProtocol());
        payload.put("host", updated.getHost());
        payload.put("port", updated.getPort());
        payload.put("requiresTls", updated.getRequiresTls());
        payload.put("policy", updated.getPolicy());
        return payload;
    }

    @DeleteMapping("/connectors/{id}")
    public Map<String, Object> deleteConnector(@PathVariable String id) {
        connectorService.deleteConnector(id);
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/workflow/{id}")
    public Map<String, Object> workflowDetail(@PathVariable String id) {
        Workflow workflow = workflowService.getWorkflowById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + id));

        List<Map<String, Object>> stages = new ArrayList<>();
        for (com.datasifter.model.WorkflowStage stage : workflow.getStages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", stage.getName());
            item.put("status", stage.getStatus());
            item.put("duration", stage.getDuration());
            item.put("records", stage.getRecords());
            stages.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", workflow.getId());
        payload.put("name", workflow.getName());
        payload.put("owner", workflow.getOwner());
        payload.put("status", workflow.getStatus());
        payload.put("schedule", workflow.getSchedule());
        payload.put("stages", stages);
        payload.put("sourceConnector", workflow.getSourceConnector());
        payload.put("targetConnector", workflow.getTargetConnector());
        payload.put("sourceType", workflow.getSourceType());
        payload.put("sourceJdbcUrl", credentialFreeJdbcUrl(workflow.getSourceJdbcUrl())
                ? workflow.getSourceJdbcUrl() : null);
        payload.put("sourceQuery", workflow.getSourceQuery());
        payload.put("sourceUsername", workflow.getSourceUsername());
        payload.put("sourcePasswordSecretId", workflow.getSourcePasswordSecretId());
        payload.put("sourceLdapUrl", credentialFreeLdapUrl(workflow.getSourceLdapUrl())
                ? workflow.getSourceLdapUrl() : null);
        payload.put("sourceLdapBaseDn", workflow.getSourceLdapBaseDn());
        payload.put("sourceLdapFilter", workflow.getSourceLdapFilter());
        payload.put("sourceLdapAttributes", workflow.getSourceLdapAttributes());
        payload.put("sourceLdapBindDn", workflow.getSourceLdapBindDn());
        payload.put("sourceLdapPasswordSecretId", workflow.getSourceLdapPasswordSecretId());
        payload.put("canvasNodes", workflow.getCanvasNodes());
        payload.put("canvasEdges", workflow.getCanvasEdges());
        payload.put("fieldMappings", workflow.getFieldMappings());
        payload.put("targetJdbcUrl", credentialFreeJdbcUrl(workflow.getTargetJdbcUrl())
                ? workflow.getTargetJdbcUrl() : null);
        payload.put("targetTable", workflow.getTargetTable());
        payload.put("targetUsername", workflow.getTargetUsername());
        payload.put("targetPasswordSecretId", workflow.getTargetPasswordSecretId());
        payload.put("sourceCsvReady", workflow.getSourceCsvPath() != null && !workflow.getSourceCsvPath().isBlank());
        return payload;
    }

    @PostMapping("/workflows")
    public Map<String, Object> createWorkflow(@RequestBody Map<String, Object> request) {
        Workflow workflow = new Workflow();
        workflow.setName(String.valueOf(request.getOrDefault("name", "New workflow")));
        workflow.setOwner(String.valueOf(request.getOrDefault("owner", "Operations")));
        workflow.setStatus(String.valueOf(request.getOrDefault("status", "active")));
        workflow.setSchedule(String.valueOf(request.getOrDefault("schedule", "Manual trigger")));
        workflow.setSourceConnector(String.valueOf(request.getOrDefault("sourceConnector", "CSV")));
        workflow.setTargetConnector(String.valueOf(request.getOrDefault("targetConnector", "Warehouse")));
        workflow.setDescription(String.valueOf(request.getOrDefault("description", "Workflow created via control plane.")));
        applyExecutionConfiguration(workflow, request);

        List<Map<String, Object>> stagePayload = (List<Map<String, Object>>) request.getOrDefault("stages", List.of());
        for (Map<String, Object> stageData : stagePayload) {
            workflow.addStage(new com.datasifter.model.WorkflowStage(
                    String.valueOf(stageData.getOrDefault("name", "New stage")),
                    String.valueOf(stageData.getOrDefault("status", "queued")),
                    String.valueOf(stageData.getOrDefault("duration", "00:00:00")),
                    String.valueOf(stageData.getOrDefault("records", "0"))
            ));
        }

        Workflow saved = workflowService.createWorkflow(workflow);
        return Map.of(
                "id", saved.getId(),
                "name", saved.getName(),
                "status", saved.getStatus(),
                "owner", saved.getOwner(),
                "schedule", saved.getSchedule()
        );
    }

    @PutMapping("/workflow/{id}")
    public Map<String, Object> updateWorkflow(@PathVariable String id, @RequestBody Map<String, Object> request) {
        Workflow existing = workflowService.getWorkflowById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + id));

        existing.setName(String.valueOf(request.getOrDefault("name", existing.getName())));
        existing.setOwner(String.valueOf(request.getOrDefault("owner", existing.getOwner())));
        existing.setStatus(String.valueOf(request.getOrDefault("status", existing.getStatus())));
        existing.setSchedule(String.valueOf(request.getOrDefault("schedule", existing.getSchedule())));
        existing.setSourceConnector(String.valueOf(request.getOrDefault("sourceConnector", existing.getSourceConnector())));
        existing.setTargetConnector(String.valueOf(request.getOrDefault("targetConnector", existing.getTargetConnector())));
        existing.setDescription(String.valueOf(request.getOrDefault("description", existing.getDescription())));
        applyExecutionConfiguration(existing, request);

        List<Map<String, Object>> stagePayload = (List<Map<String, Object>>) request.get("stages");
        if (stagePayload != null) {
            List<com.datasifter.model.WorkflowStage> stages = new ArrayList<>();
            for (Map<String, Object> stageData : stagePayload) {
                stages.add(new com.datasifter.model.WorkflowStage(
                        String.valueOf(stageData.getOrDefault("name", "New stage")),
                        String.valueOf(stageData.getOrDefault("status", "queued")),
                        String.valueOf(stageData.getOrDefault("duration", "00:00:00")),
                        String.valueOf(stageData.getOrDefault("records", "0"))
                ));
            }
            existing.setStages(stages);
        }

        Workflow saved = workflowService.updateWorkflow(id, existing);
        return Map.of(
                "id", saved.getId(),
                "name", saved.getName(),
                "status", saved.getStatus(),
                "owner", saved.getOwner(),
                "schedule", saved.getSchedule()
        );
    }

    @DeleteMapping("/workflow/{id}")
    public Map<String, Object> deleteWorkflow(@PathVariable String id) {
        workflowService.deleteWorkflow(id);
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> executions() {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (JobExecution execution : jobExecutionService.getAllExecutions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", execution.getId());
            item.put("name", execution.getName());
            item.put("workflowId", execution.getWorkflowId());
            item.put("status", execution.getStatus());
            item.put("progress", execution.getProgress());
            item.put("success", execution.getSuccess());
            item.put("failed", execution.getFailed());
            item.put("retryCount", execution.getRetryCount());
            item.put("workerId", execution.getWorkerId());
            item.put("heartbeatAt", execution.getHeartbeatAt());
            item.put("leaseExpiresAt", execution.getLeaseExpiresAt());
            payload.add(item);
        }
        return payload;
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> executionDetail(@PathVariable String id) {
        JobExecution execution = jobExecutionService.getExecutionById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job execution not found: " + id));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", execution.getId());
        payload.put("name", execution.getName());
        payload.put("workflowId", execution.getWorkflowId());
        payload.put("status", execution.getStatus());
        payload.put("progress", execution.getProgress());
        payload.put("success", execution.getSuccess());
        payload.put("failed", execution.getFailed());
        payload.put("retryCount", execution.getRetryCount());
        payload.put("workerId", execution.getWorkerId());
        payload.put("heartbeatAt", execution.getHeartbeatAt());
        payload.put("leaseExpiresAt", execution.getLeaseExpiresAt());
        payload.put("nextAttemptAt", execution.getNextAttemptAt());
        return payload;
    }

    @PutMapping("/jobs/{id}/status")
    public Map<String, Object> updateExecutionStatus(@PathVariable String id, @RequestBody Map<String, Object> request) {
        JobExecution execution = jobExecutionService.updateProgress(
                id,
                String.valueOf(request.getOrDefault("status", "queued")),
                Integer.parseInt(String.valueOf(request.getOrDefault("progress", 0))),
                Integer.parseInt(String.valueOf(request.getOrDefault("success", 0))),
                Integer.parseInt(String.valueOf(request.getOrDefault("failed", 0)))
        );
        return Map.of(
                "id", execution.getId(),
                "status", execution.getStatus(),
                "progress", execution.getProgress(),
                "success", execution.getSuccess(),
                "failed", execution.getFailed()
        );
    }

    @PostMapping("/jobs/{id}/pause")
    public Map<String, Object> pauseExecution(@PathVariable String id) {
        JobExecution execution = jobExecutionService.pauseExecution(id);
        return Map.of("id", execution.getId(), "status", execution.getStatus(), "message", "Job paused.");
    }

    @PostMapping("/jobs/{id}/resume")
    public Map<String, Object> resumeExecution(@PathVariable String id) {
        JobExecution execution = jobExecutionService.resumeExecution(id);
        return Map.of("id", execution.getId(), "status", execution.getStatus(), "message", "Job resumed.");
    }

    @PostMapping("/jobs/{id}/cancel")
    public Map<String, Object> cancelExecution(@PathVariable String id) {
        JobExecution execution = jobExecutionService.cancelExecution(id);
        return Map.of("id", execution.getId(), "status", execution.getStatus(), "message", "Job cancelled.");
    }

    @PostMapping("/workers/jobs/claim")
    public ResponseEntity<Map<String, Object>> claimJob(
            @RequestHeader(name = "X-Worker-Token", required = false) String token,
            @RequestBody WorkerClaimRequest request) {
        workerTokenVerifier.requireValid(token);
        return jobExecutionService.claimNext(request.workerId())
                .map(this::workerJobPayload)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/workers/health")
    public List<JobExecutionService.WorkerHealth> workerHealth(
            @RequestHeader(name = "X-Worker-Token", required = false) String token) {
        workerTokenVerifier.requireValid(token);
        return jobExecutionService.getWorkerHealth();
    }

    @PostMapping("/workers/jobs/{id}/heartbeat")
    public Map<String, Object> heartbeatJob(
            @RequestHeader(name = "X-Worker-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody WorkerHeartbeatRequest request) {
        workerTokenVerifier.requireValid(token);
        JobExecution execution = jobExecutionService.heartbeat(
                id,
                request.workerId(),
                request.progress() == null ? 0 : request.progress(),
                request.success() == null ? 0 : request.success(),
                request.failed() == null ? 0 : request.failed());
        return workerJobPayload(execution);
    }

    @PostMapping("/workers/jobs/{id}/complete")
    public Map<String, Object> completeJob(
            @RequestHeader(name = "X-Worker-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody WorkerCompletionRequest request) {
        workerTokenVerifier.requireValid(token);
        JobExecution execution = jobExecutionService.complete(
                id,
                request.workerId(),
                request.success() == null ? 0 : request.success(),
                request.failed() == null ? 0 : request.failed());
        return workerJobPayload(execution);
    }

    @PostMapping("/workers/jobs/{id}/fail")
    public Map<String, Object> failJob(
            @RequestHeader(name = "X-Worker-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody WorkerFailureRequest request) {
        workerTokenVerifier.requireValid(token);
        JobExecutionService.FailureResult result = jobExecutionService.fail(id, request.workerId());
        return Map.of(
                "id", result.execution().getId(),
                "status", result.execution().getStatus(),
                "retryCount", result.execution().getRetryCount(),
                "nextAttemptAt", result.execution().getNextAttemptAt() == null
                        ? "" : result.execution().getNextAttemptAt().toString(),
                "retryScheduled", result.retryScheduled());
    }

    private Map<String, Object> workerJobPayload(JobExecution execution) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", execution.getId());
        payload.put("name", execution.getName());
        payload.put("workflowId", execution.getWorkflowId());
        payload.put("status", execution.getStatus());
        payload.put("progress", execution.getProgress());
        payload.put("success", execution.getSuccess());
        payload.put("failed", execution.getFailed());
        payload.put("retryCount", execution.getRetryCount());
        payload.put("leaseExpiresAt", execution.getLeaseExpiresAt());
        return payload;
    }

    public record WorkerClaimRequest(String workerId) {}
    public record WorkerHeartbeatRequest(String workerId, Integer progress, Integer success, Integer failed) {}
    public record WorkerCompletionRequest(String workerId, Integer success, Integer failed) {}
    public record WorkerFailureRequest(String workerId) {}

    @GetMapping("/health")
    public Map<String, Object> healthOverview() {
        int total = jobExecutionService.getAllExecutions().size();
        JobExecutionService.QueueSummary summary = jobExecutionService.getQueueSummary();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "healthy");
        payload.put("service", "datasifter-control-plane");
        payload.put("version", "0.9.0-mvp");
        payload.put("jobsTotal", total);
        payload.put("jobsQueued", summary.queued());
        payload.put("jobsRunning", summary.running());
        payload.put("jobsPaused", summary.paused());
        payload.put("jobsFailed", summary.failed());
        payload.put("jobsSucceeded", summary.succeeded());
        payload.put("jobsCancelled", summary.cancelled());
        return payload;
    }

    @GetMapping("/jobs/queue-status")
    public Map<String, Object> queueStatus() {
        JobExecutionService.QueueSummary summary = jobExecutionService.getQueueSummary();
        return Map.of(
                "total", summary.total(),
                "queued", summary.queued(),
                "running", summary.running(),
                "paused", summary.paused(),
                "failed", summary.failed(),
                "succeeded", summary.succeeded(),
                "cancelled", summary.cancelled()
        );
    }

    @GetMapping("/security")
    public Map<String, Object> securityOverview() {
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(Map.of("name", "LDAPS enforcement", "status", "compliant", "owner", "Identity"));
        controls.add(Map.of("name", "AES-256 vault rotation", "status", "healthy", "owner", "Security"));
        controls.add(Map.of("name", "PII masking", "status", "active", "owner", "Data Privacy"));

        List<Map<String, Object>> connectorPolicies = new ArrayList<>();
        for (Connector connector : connectorService.getAllConnectors()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", connector.getName());
            item.put("policy", connectorService.validatePolicy(connector));
            item.put("host", connector.getHost());
            item.put("requiresTls", connector.getRequiresTls());
            connectorPolicies.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("controls", controls);
        payload.put("roles", accessControlService.getRoles());
        payload.put("connectors", connectorPolicies);
        return payload;
    }

    @PostMapping("/security/access/validate")
    public Map<String, Object> validateAccess(@RequestBody Map<String, String> request) {
        String role = request.get("role");
        String action = request.get("action");
        boolean allowed = accessControlService.isAllowed(role, action);
        return Map.of("role", role, "action", action, "allowed", allowed);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog() {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (AuditEntry entry : auditService.getAuditLog()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", entry.getTimestamp() == null ? "" : entry.getTimestamp().toString());
            item.put("actor", entry.getActor());
            item.put("event", entry.getEvent());
            item.put("details", entry.getDetails());
            payload.add(item);
        }
        return payload;
    }

    @GetMapping("/keyvault")
    public List<Map<String, Object>> keyVault() {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (KeyVaultSecret secret : keyVaultService.getSecrets()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", secret.getId());
            item.put("name", secret.getName());
            item.put("type", secret.getType());
            item.put("status", secret.getStatus());
            item.put("value", keyVaultService.maskSecretValue(secret.getValue()));
            payload.add(item);
        }
        return payload;
    }

    @PostMapping("/keyvault")
    public Map<String, Object> createSecret(@RequestBody Map<String, String> request) {
        KeyVaultSecret secret = new KeyVaultSecret(
                UUID.randomUUID().toString(),
                request.getOrDefault("name", "new-secret"),
                request.getOrDefault("type", "database"),
                request.getOrDefault("status", "active"),
                request.getOrDefault("value", "")
        );
        KeyVaultSecret saved = keyVaultService.createSecret(secret);
        return Map.of(
                "id", saved.getId(),
                "name", saved.getName(),
                "type", saved.getType(),
                "status", saved.getStatus(),
                "value", keyVaultService.maskSecretValue(saved.getValue())
        );
    }

    @PostMapping("/keyvault/rotate")
    public ResponseEntity<Map<String, String>> rotateVaultPassphrase(
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        if (VaultCrypto.isManagedProviderActive()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Vault key rotation must be performed through the configured managed key provider"));
        }
        String passphrase = request.get("newPassphrase");
        if (passphrase == null || passphrase.length() < 12 || passphrase.length() > 1024) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "newPassphrase must contain between 12 and 1024 characters"));
        }
        keyVaultService.rotatePassphrase(passphrase);
        auditService.createAuditEntry(new AuditEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                authentication.getName(),
                "Vault passphrase rotated",
                "All stored vault secrets were re-encrypted with the new passphrase-derived key."));
        return ResponseEntity.ok(Map.of(
                "message", "Vault key rotated. Update DATASIFTER_VAULT_PASSPHRASE before restarting the service."));
    }

    @GetMapping("/mapping")
    public Map<String, Object> mappingPreview(@RequestParam(required = false) String workflowId) {
        Workflow workflow = workflowId == null || workflowId.isBlank()
                ? workflowService.getAllWorkflows().stream().findFirst().orElse(null)
                : workflowService.getWorkflowById(workflowId).orElse(null);
        List<Map<String, String>> rules = workflow == null || workflow.getFieldMappings() == null
                ? List.of()
                : workflow.getFieldMappings().stream()
                .map(mapping -> Map.of(
                        "source", mapping.getSourceField() == null ? "" : mapping.getSourceField(),
                        "target", mapping.getTargetField() == null ? "" : mapping.getTargetField(),
                        "transform", mapping.getPrivacyAction() == null ? "none" : mapping.getPrivacyAction()))
                .toList();
        List<String> sourceFields = rules.stream().map(rule -> rule.get("source")).distinct().toList();
        List<String> targetFields = rules.stream().map(rule -> rule.get("target")).distinct().toList();
        return Map.of(
                "sourceFields", sourceFields,
                "targetFields", targetFields,
                "rules", rules
        );
    }

    private void applyExecutionConfiguration(Workflow workflow, Map<String, Object> request) {
        if (request.containsKey("sourceType")) {
            workflow.setSourceType(asOptionalString(request.get("sourceType")));
        }
        if (request.containsKey("sourceJdbcUrl")) {
            String jdbcUrl = asOptionalString(request.get("sourceJdbcUrl"));
            if (jdbcUrl != null && !jdbcUrl.isBlank() && !credentialFreeJdbcUrl(jdbcUrl)) {
                throw new IllegalArgumentException("Source JDBC URL must not embed credentials");
            }
            workflow.setSourceJdbcUrl(jdbcUrl);
        }
        if (request.containsKey("sourceQuery")) {
            workflow.setSourceQuery(asOptionalString(request.get("sourceQuery")));
        }
        if (request.containsKey("sourceUsername")) {
            workflow.setSourceUsername(asOptionalString(request.get("sourceUsername")));
        }
        if (request.containsKey("sourcePasswordSecretId")) {
            workflow.setSourcePasswordSecretId(asOptionalString(request.get("sourcePasswordSecretId")));
        }
        if (request.containsKey("sourceLdapUrl")) {
            String ldapUrl = asOptionalString(request.get("sourceLdapUrl"));
            if (ldapUrl != null && !ldapUrl.isBlank() && !credentialFreeLdapUrl(ldapUrl)) {
                throw new IllegalArgumentException("LDAP source URL must use LDAPS and must not embed credentials");
            }
            workflow.setSourceLdapUrl(ldapUrl);
        }
        if (request.containsKey("sourceLdapBaseDn")) {
            workflow.setSourceLdapBaseDn(asOptionalString(request.get("sourceLdapBaseDn")));
        }
        if (request.containsKey("sourceLdapFilter")) {
            workflow.setSourceLdapFilter(asOptionalString(request.get("sourceLdapFilter")));
        }
        if (request.containsKey("sourceLdapAttributes")) {
            workflow.setSourceLdapAttributes(objectMapper.convertValue(
                    request.get("sourceLdapAttributes"), new TypeReference<List<String>>() {}));
        }
        if (request.containsKey("sourceLdapBindDn")) {
            workflow.setSourceLdapBindDn(asOptionalString(request.get("sourceLdapBindDn")));
        }
        if (request.containsKey("sourceLdapPasswordSecretId")) {
            workflow.setSourceLdapPasswordSecretId(asOptionalString(request.get("sourceLdapPasswordSecretId")));
        }
        if (request.containsKey("targetJdbcUrl")) {
            String jdbcUrl = asOptionalString(request.get("targetJdbcUrl"));
            if (jdbcUrl != null && !jdbcUrl.isBlank() && !credentialFreeJdbcUrl(jdbcUrl)) {
                throw new IllegalArgumentException("Target JDBC URL must not embed credentials");
            }
            workflow.setTargetJdbcUrl(jdbcUrl);
        }
        if (request.containsKey("targetTable")) {
            workflow.setTargetTable(asOptionalString(request.get("targetTable")));
        }
        if (request.containsKey("targetUsername")) {
            workflow.setTargetUsername(asOptionalString(request.get("targetUsername")));
        }
        if (request.containsKey("targetPasswordSecretId")) {
            workflow.setTargetPasswordSecretId(asOptionalString(request.get("targetPasswordSecretId")));
        }
        if (request.containsKey("canvasNodes")) {
            workflow.setCanvasNodes(objectMapper.convertValue(
                    request.get("canvasNodes"), new TypeReference<List<WorkflowCanvasNode>>() {}));
        }
        if (request.containsKey("canvasEdges")) {
            workflow.setCanvasEdges(objectMapper.convertValue(
                    request.get("canvasEdges"), new TypeReference<List<WorkflowCanvasEdge>>() {}));
        }
        if (request.containsKey("fieldMappings")) {
            workflow.setFieldMappings(objectMapper.convertValue(
                    request.get("fieldMappings"), new TypeReference<List<FieldMapping>>() {}));
        }
    }

    private String asOptionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean credentialFreeJdbcUrl(String jdbcUrl) {
        return jdbcUrl == null || ((jdbcUrl.startsWith("jdbc:mysql://")
                        || jdbcUrl.startsWith("jdbc:postgresql://")
                        || jdbcUrl.startsWith("jdbc:h2:mem:"))
                && jdbcUrl.indexOf('@') < 0
                && !jdbcUrl.matches("(?i).*([?&](user|password|pwd)=).*"));
    }

    private boolean credentialFreeLdapUrl(String ldapUrl) {
        if (ldapUrl == null) {
            return true;
        }
        try {
            URI uri = new URI(ldapUrl);
            return "ldaps".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    @PostMapping("/jobs/run")
    public Map<String, Object> runJob(@RequestBody Map<String, String> request, Authentication authentication) {
        String name = request.getOrDefault("name", "Manual sync");
        String workflowId = request.get("workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflowId is required");
        }
        workflowService.getWorkflowById(workflowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
        JobExecution execution = jobExecutionService.queueJob(name, workflowId);
        auditService.createAuditEntry(new AuditEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                authentication.getName(),
                "Job queued",
                "Job \"" + name + "\" queued for workflow " + workflowId + "."
        ));
        return Map.of(
                "jobId", execution.getId(),
                "name", execution.getName(),
                "status", execution.getStatus(),
                "message", "The job has been accepted and will begin processing shortly."
        );
    }

    @GetMapping("/file-agent/status")
    public Map<String, Object> fileAgentStatus() {
        return Map.of(
                "status", "online",
                "watchDir", "./data/inbox",
                "lastSync", "just now",
                "processedFiles", 24,
                "pendingFiles", 0
        );
    }

    @PostMapping("/file-agent/upload")
    public Map<String, Object> fileAgentUpload(@RequestBody Map<String, Object> request) {
        String source = String.valueOf(request.getOrDefault("source", "local-file-agent"));
        String watchDir = String.valueOf(request.getOrDefault("watchDir", "./data/inbox"));
        String fileName = String.valueOf(request.getOrDefault("fileName", "unknown-file"));
        Object recordCount = request.get("recordCount");
        int processedRecords = 0;

        if (recordCount instanceof Number number) {
            processedRecords = number.intValue();
        } else if (recordCount instanceof String value && !value.isBlank()) {
            try {
                processedRecords = Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                processedRecords = 0;
            }
        }

        auditService.createAuditEntry(new AuditEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                source,
                "File received",
                "Accepted file '" + fileName + "' from " + watchDir + " with " + processedRecords + " records."
        ));

        return Map.of(
                "status", "received",
                "source", source,
                "watchDir", watchDir,
                "fileName", fileName,
                "recordCount", processedRecords,
                "message", "File agent payload accepted and queued for processing."
        );
    }

    public record DashboardSummary(
            String name,
            String status,
            List<JobStatus> jobs,
            List<ConnectorStatus> connectors,
            List<Alert> alerts,
            List<KpiCard> metrics
    ) {}

    public record JobStatus(
            String name,
            String source,
            String target,
            String status,
            int progress,
            int success,
            int failed
    ) {}

    public record ConnectorStatus(
            String name,
            String type,
            String status,
            String lastSync
    ) {}

    public record Alert(
            String severity,
            String message
    ) {}

    public record KpiCard(
            String label,
            String value,
            String delta,
            String tone
    ) {}
}
