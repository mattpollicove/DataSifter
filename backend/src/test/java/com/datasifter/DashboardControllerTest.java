package com.datasifter;

import com.datasifter.config.JobQueueProperties;
import com.datasifter.config.SecurityConfig;
import com.datasifter.config.VaultConfiguration;
import com.datasifter.model.JobExecution;
import com.datasifter.model.Workflow;
import com.datasifter.repository.InMemoryAuditRepository;
import com.datasifter.repository.InMemoryConnectorRepository;
import com.datasifter.repository.InMemoryJobExecutionRepository;
import com.datasifter.repository.InMemoryKeyVaultRepository;
import com.datasifter.repository.InMemoryWorkflowRepository;
import com.datasifter.service.AccessControlService;
import com.datasifter.service.AuditService;
import com.datasifter.service.ConnectorService;
import com.datasifter.service.JobExecutionService;
import com.datasifter.service.KeyVaultService;
import com.datasifter.service.WorkflowService;
import com.datasifter.service.WorkerTokenVerifier;
import com.datasifter.support.JsonFileStore;
import com.datasifter.support.LocalWorkflowFileStore;
import com.datasifter.support.WorkflowFileStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import jakarta.servlet.http.Cookie;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@ActiveProfiles("json")
@WithMockUser(roles = "ADMIN")
@TestPropertySource(properties = {
        "datasifter.security.admin.username=test-admin",
        "datasifter.security.admin.password=test-admin-password",
        "datasifter.vault.passphrase=test-vault-passphrase-25"
})
@Import({
        SecurityConfig.class,
        VaultConfiguration.class,
        InMemoryWorkflowRepository.class,
        WorkflowService.class,
        InMemoryConnectorRepository.class,
        ConnectorService.class,
        InMemoryAuditRepository.class,
        AuditService.class,
        InMemoryKeyVaultRepository.class,
        KeyVaultService.class,
        InMemoryJobExecutionRepository.class,
        JobExecutionService.class,
        AccessControlService.class,
        JobQueueProperties.class,
        WorkerTokenVerifier.class,
        DashboardControllerTest.FileStoreTestConfiguration.class
})
class DashboardControllerTest {
    @TestConfiguration
    static class FileStoreTestConfiguration {
        @Bean
        WorkflowFileStore workflowFileStore() {
            return new LocalWorkflowFileStore("");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobExecutionService jobExecutionService;
    @Autowired
    private WorkflowService workflowService;

    private RequestPostProcessor csrf() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "test-csrf-token"));
            request.addHeader("X-XSRF-TOKEN", "test-csrf-token");
            return request;
        };
    }

    @Test
    void controlPlaneRequiresAuthenticationAndEnforcesRoleAndCsrfPolicies() throws Exception {
        mockMvc.perform(get("/api/dashboard").with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/dashboard").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/workflows")
                        .with(user("viewer").roles("VIEWER"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"forbidden\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/keyvault").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/workflows")
                        .with(user("operator").roles("OPERATOR"))
                        .contentType("application/json")
                        .content("{\"name\":\"csrf required\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csvUploadStoresActualFileAndLinksItToWorkflow() throws Exception {
        String workflowId = java.util.UUID.randomUUID().toString();
        workflowService.createWorkflow(new Workflow(workflowId, "CSV upload test", "QA", "active", "manual"));
        MockMultipartFile csv = new MockMultipartFile(
                "file", "people.csv", "text/csv", "email\nperson@example.com\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/workflows/" + workflowId + "/csv")
                        .file(csv)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId))
                .andExpect(jsonPath("$.status").value("ready"));

        Workflow stored = workflowService.getWorkflowById(workflowId).orElseThrow();
        String reference = stored.getSourceCsvPath();
        org.junit.jupiter.api.Assertions.assertTrue(reference.startsWith("local:"));
        java.nio.file.Path uploaded = JsonFileStore.resolveDataDirectory().resolve("uploads")
                .resolve(reference.substring("local:".length()));
        try {
            try (var input = new LocalWorkflowFileStore("").open(reference)) {
                org.junit.jupiter.api.Assertions.assertEquals(
                        "email\nperson@example.com\n",
                        new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(uploaded);
        }
    }

    @Test
    void dashboardEndpointReturnsSummary() throws Exception {
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("DataSifter"))
                .andExpect(jsonPath("$.status").value("healthy"))
                .andExpect(jsonPath("$.jobs").isArray());
    }

    @Test
    void workflowAndSecurityEndpointsExposeOperationalDetails() throws Exception {
        MvcResult workflowResult = mockMvc.perform(post("/api/workflows").with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"HR User Provisioning\",\"owner\":\"People Ops\",\"status\":\"active\",\"schedule\":\"Nightly 02:00 UTC\","
                                + "\"canvasNodes\":[{\"id\":\"source-1\",\"type\":\"source\",\"name\":\"CSV\",\"config\":\"input.csv\",\"x\":30,\"y\":40}],"
                                + "\"canvasEdges\":[],"
                                + "\"fieldMappings\":[{\"sourceField\":\"email\",\"targetField\":\"email_hash\",\"privacyAction\":\"sha256\"}],"
                                + "\"targetJdbcUrl\":\"jdbc:mysql://localhost:3306/target\",\"targetTable\":\"users\","
                                + "\"targetUsername\":\"sync-user\",\"targetPasswordSecretId\":\"db-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HR User Provisioning"))
                .andReturn();

        String createdWorkflowId = extractJsonValue(workflowResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/workflow/" + createdWorkflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HR User Provisioning"))
                .andExpect(jsonPath("$.canvasNodes[0].id").value("source-1"))
                .andExpect(jsonPath("$.fieldMappings[0].privacyAction").value("sha256"))
                .andExpect(jsonPath("$.targetTable").value("users"));

        mockMvc.perform(put("/api/workflow/" + createdWorkflowId).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"HR User Provisioning\",\"owner\":\"People Ops\",\"status\":\"active\","
                                + "\"schedule\":\"Nightly 02:00 UTC\",\"canvasNodes\":[],\"canvasEdges\":[],"
                                + "\"fieldMappings\":[{\"sourceField\":\"email\",\"targetField\":\"email_hash\",\"privacyAction\":\"mask\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workflow/" + createdWorkflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldMappings[0].privacyAction").value("mask"));

        mockMvc.perform(get("/api/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controls[0].name").value("LDAPS enforcement"));
    }

    @Test
    void workflowAndJobApisSupportCreationAndTracking() throws Exception {
        MvcResult workflowResult = mockMvc.perform(post("/api/workflows").with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"Payroll Sync\",\"owner\":\"Finance\",\"status\":\"active\",\"schedule\":\"Daily\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Payroll Sync"))
                .andReturn();

        String workflowId = extractJsonValue(workflowResult.getResponse().getContentAsString(), "id");

        MvcResult runResult = mockMvc.perform(post("/api/jobs/run").with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"Payroll Sync\",\"workflowId\":\"" + workflowId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn();

        String responseBody = runResult.getResponse().getContentAsString();
        String jobId = extractJsonValue(responseBody, "jobId");

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").exists());

        mockMvc.perform(post("/api/jobs/" + jobId + "/pause").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paused"));

        mockMvc.perform(get("/api/jobs/queue-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.queued").exists());

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("datasifter-control-plane"));
    }

    @Test
    void workerApiRequiresTokenAndEnforcesLeaseOwnership() throws Exception {
        jobExecutionService.getAllExecutions().stream()
                .filter(execution -> "queued".equalsIgnoreCase(execution.getStatus()))
                .forEach(execution -> jobExecutionService.pauseExecution(execution.getId()));
        JobExecution execution = jobExecutionService.queueJob("Worker protocol test", "wf-worker-test");

        mockMvc.perform(post("/api/workers/jobs/claim")
                        .contentType("application/json")
                        .content("{\"workerId\":\"worker-test\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/workers/jobs/claim")
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType("application/json")
                        .content("{\"workerId\":\"worker-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(execution.getId()))
                .andExpect(jsonPath("$.status").value("running"));

        mockMvc.perform(get("/api/workers/health")
                        .header("X-Worker-Token", "test-worker-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("healthy"));

        mockMvc.perform(put("/api/jobs/" + execution.getId() + "/status").with(csrf())
                        .contentType("application/json")
                        .content("{\"status\":\"running\",\"progress\":90,\"success\":90,\"failed\":0}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/workers/jobs/" + execution.getId() + "/heartbeat")
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType("application/json")
                        .content("{\"workerId\":\"wrong-worker\",\"progress\":50,\"success\":5,\"failed\":0}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/workers/jobs/" + execution.getId() + "/heartbeat")
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType("application/json")
                        .content("{\"workerId\":\"worker-test\",\"progress\":50,\"success\":5,\"failed\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress").value(50));

        mockMvc.perform(post("/api/workers/jobs/" + execution.getId() + "/complete")
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType("application/json")
                        .content("{\"workerId\":\"worker-test\",\"success\":10,\"failed\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        JobExecution retryExecution = jobExecutionService.queueJob("Worker retry test", "wf-worker-retry");
        mockMvc.perform(post("/api/workers/jobs/claim")
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType("application/json")
                        .content("{\"workerId\":\"worker-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(retryExecution.getId()));

        mockMvc.perform(post("/api/workers/jobs/" + retryExecution.getId() + "/fail")
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType("application/json")
                        .content("{\"workerId\":\"worker-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.retryCount").value(1))
                .andExpect(jsonPath("$.retryScheduled").value(true));

        mockMvc.perform(post("/api/workers/jobs/claim")
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType("application/json")
                        .content("{\"workerId\":\"worker-test\"}"))
                .andExpect(status().isNoContent());
    }

    private String extractJsonValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int startIndex = json.indexOf(marker);
        if (startIndex < 0) {
            throw new AssertionError("Expected JSON field '" + key + "' in response: " + json);
        }
        startIndex += marker.length();
        int endIndex = json.indexOf('"', startIndex);
        return json.substring(startIndex, endIndex);
    }
}
