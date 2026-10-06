package com.datasifter.controller;

import com.datasifter.model.Workflow;
import com.datasifter.model.AuditEntry;
import com.datasifter.service.AuditService;
import com.datasifter.service.WorkflowService;
import com.datasifter.support.WorkflowFileStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;

@RestController
public class WorkflowCsvController {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowCsvController.class);
    private static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
    private final WorkflowService workflowService;
    private final AuditService auditService;
    private final WorkflowFileStore fileStore;

    public WorkflowCsvController(
            WorkflowService workflowService, AuditService auditService, WorkflowFileStore fileStore) {
        this.workflowService = workflowService;
        this.auditService = auditService;
        this.fileStore = fileStore;
    }

    @PostMapping(path = "/api/workflows/{id}/csv", consumes = "multipart/form-data")
    public Map<String, Object> uploadCsv(
            @PathVariable String id,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        Workflow workflow = workflowService.getWorkflowById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV upload must be between 1 byte and 100 MiB");
        }
        if (originalName == null || !originalName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .csv files are accepted");
        }

        String previousReference = workflow.getSourceCsvPath();
        String inputReference = null;
        try (var stream = file.getInputStream()) {
            inputReference = fileStore.store(stream, originalName);
            workflow.setSourceCsvPath(inputReference);
            workflowService.updateWorkflow(id, workflow);
        } catch (IOException | RuntimeException e) {
            if (inputReference != null) {
                try {
                    fileStore.delete(inputReference);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store CSV input", e);
        }
        if (previousReference != null && !previousReference.isBlank() && !previousReference.equals(inputReference)) {
            try {
                fileStore.delete(previousReference);
            } catch (IOException cleanupFailure) {
                LOGGER.warn("Unable to remove replaced workflow input for workflow {}", id, cleanupFailure);
            }
        }
        auditService.createAuditEntry(new AuditEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                authentication.getName(),
                "Workflow CSV uploaded",
                "CSV input of " + file.getSize() + " bytes attached to workflow " + id + "."));
        String safeFileName = Path.of(originalName.replace('\\', '/')).getFileName().toString();
        return Map.of("workflowId", id, "fileName", safeFileName, "sizeBytes", file.getSize(), "status", "ready");
    }
}
