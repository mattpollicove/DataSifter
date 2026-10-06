package com.datasifter.service;

import com.datasifter.model.Workflow;
import com.datasifter.model.WorkflowStage;
import com.datasifter.repository.WorkflowRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {
    private final WorkflowRepository repository;

    public WorkflowService(WorkflowRepository repository) {
        this.repository = repository;
    }

    public List<Workflow> getAllWorkflows() {
        return repository.findAll();
    }

    public Optional<Workflow> getWorkflowById(String id) {
        return repository.findById(id);
    }

    public Workflow createWorkflow(Workflow workflow) {
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow cannot be null");
        }
        return repository.save(workflow);
    }

    public Workflow updateWorkflow(String id, Workflow updatedWorkflow) {
        Workflow existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + id));

        existing.setName(updatedWorkflow.getName());
        existing.setOwner(updatedWorkflow.getOwner());
        existing.setStatus(updatedWorkflow.getStatus());
        existing.setSchedule(updatedWorkflow.getSchedule());
        existing.setDescription(updatedWorkflow.getDescription());
        existing.setSourceConnector(updatedWorkflow.getSourceConnector());
        existing.setTargetConnector(updatedWorkflow.getTargetConnector());
        existing.setSourceType(updatedWorkflow.getSourceType());
        existing.setStages(updatedWorkflow.getStages());
        existing.setSourceJdbcUrl(updatedWorkflow.getSourceJdbcUrl());
        existing.setSourceQuery(updatedWorkflow.getSourceQuery());
        existing.setSourceUsername(updatedWorkflow.getSourceUsername());
        existing.setSourcePasswordSecretId(updatedWorkflow.getSourcePasswordSecretId());
        existing.setSourceLdapUrl(updatedWorkflow.getSourceLdapUrl());
        existing.setSourceLdapBaseDn(updatedWorkflow.getSourceLdapBaseDn());
        existing.setSourceLdapFilter(updatedWorkflow.getSourceLdapFilter());
        existing.setSourceLdapAttributes(updatedWorkflow.getSourceLdapAttributes());
        existing.setSourceLdapBindDn(updatedWorkflow.getSourceLdapBindDn());
        existing.setSourceLdapPasswordSecretId(updatedWorkflow.getSourceLdapPasswordSecretId());
        existing.setTargetJdbcUrl(updatedWorkflow.getTargetJdbcUrl());
        existing.setTargetTable(updatedWorkflow.getTargetTable());
        existing.setTargetUsername(updatedWorkflow.getTargetUsername());
        existing.setTargetPasswordSecretId(updatedWorkflow.getTargetPasswordSecretId());
        existing.setCanvasNodes(updatedWorkflow.getCanvasNodes());
        existing.setCanvasEdges(updatedWorkflow.getCanvasEdges());
        existing.setFieldMappings(updatedWorkflow.getFieldMappings());
        existing.setSourceCsvPath(updatedWorkflow.getSourceCsvPath());
        return repository.save(existing);
    }

    public Workflow addStage(String workflowId, WorkflowStage stage) {
        Workflow workflow = repository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));
        workflow.addStage(stage);
        return repository.save(workflow);
    }

    public void deleteWorkflow(String id) {
        repository.deleteById(id);
    }
}
