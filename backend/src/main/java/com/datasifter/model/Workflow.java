package com.datasifter.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Workflow {
    private String id;
    private String name;
    private String owner;
    private String status;
    private String schedule;
    private String description;
    private String sourceConnector;
    private String targetConnector;
    private String sourceType = "csv";
    private String sourceCsvPath;
    private String sourceJdbcUrl;
    private String sourceQuery;
    private String sourceUsername;
    private String sourcePasswordSecretId;
    private String sourceLdapUrl;
    private String sourceLdapBaseDn;
    private String sourceLdapFilter;
    private List<String> sourceLdapAttributes = new ArrayList<>();
    private String sourceLdapBindDn;
    private String sourceLdapPasswordSecretId;
    private String targetJdbcUrl;
    private String targetTable;
    private String targetUsername;
    private String targetPasswordSecretId;
    private Instant createdAt;
    private Instant updatedAt;
    private List<WorkflowStage> stages = new ArrayList<>();
    private List<WorkflowCanvasNode> canvasNodes = new ArrayList<>();
    private List<WorkflowCanvasEdge> canvasEdges = new ArrayList<>();
    private List<FieldMapping> fieldMappings = new ArrayList<>();

    public Workflow() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Workflow(String id, String name, String owner, String status, String schedule) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.status = status;
        this.schedule = schedule;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSourceConnector() { return sourceConnector; }
    public void setSourceConnector(String sourceConnector) { this.sourceConnector = sourceConnector; }

    public String getTargetConnector() { return targetConnector; }
    public void setTargetConnector(String targetConnector) { this.targetConnector = targetConnector; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourceCsvPath() { return sourceCsvPath; }
    public void setSourceCsvPath(String sourceCsvPath) { this.sourceCsvPath = sourceCsvPath; }

    public String getSourceJdbcUrl() { return sourceJdbcUrl; }
    public void setSourceJdbcUrl(String sourceJdbcUrl) { this.sourceJdbcUrl = sourceJdbcUrl; }

    public String getSourceQuery() { return sourceQuery; }
    public void setSourceQuery(String sourceQuery) { this.sourceQuery = sourceQuery; }

    public String getSourceUsername() { return sourceUsername; }
    public void setSourceUsername(String sourceUsername) { this.sourceUsername = sourceUsername; }

    public String getSourcePasswordSecretId() { return sourcePasswordSecretId; }
    public void setSourcePasswordSecretId(String sourcePasswordSecretId) { this.sourcePasswordSecretId = sourcePasswordSecretId; }

    public String getSourceLdapUrl() { return sourceLdapUrl; }
    public void setSourceLdapUrl(String sourceLdapUrl) { this.sourceLdapUrl = sourceLdapUrl; }

    public String getSourceLdapBaseDn() { return sourceLdapBaseDn; }
    public void setSourceLdapBaseDn(String sourceLdapBaseDn) { this.sourceLdapBaseDn = sourceLdapBaseDn; }

    public String getSourceLdapFilter() { return sourceLdapFilter; }
    public void setSourceLdapFilter(String sourceLdapFilter) { this.sourceLdapFilter = sourceLdapFilter; }

    public List<String> getSourceLdapAttributes() { return sourceLdapAttributes; }
    public void setSourceLdapAttributes(List<String> sourceLdapAttributes) {
        this.sourceLdapAttributes = sourceLdapAttributes == null ? new ArrayList<>() : new ArrayList<>(sourceLdapAttributes);
    }

    public String getSourceLdapBindDn() { return sourceLdapBindDn; }
    public void setSourceLdapBindDn(String sourceLdapBindDn) { this.sourceLdapBindDn = sourceLdapBindDn; }

    public String getSourceLdapPasswordSecretId() { return sourceLdapPasswordSecretId; }
    public void setSourceLdapPasswordSecretId(String sourceLdapPasswordSecretId) { this.sourceLdapPasswordSecretId = sourceLdapPasswordSecretId; }

    public String getTargetJdbcUrl() { return targetJdbcUrl; }
    public void setTargetJdbcUrl(String targetJdbcUrl) { this.targetJdbcUrl = targetJdbcUrl; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public String getTargetUsername() { return targetUsername; }
    public void setTargetUsername(String targetUsername) { this.targetUsername = targetUsername; }

    public String getTargetPasswordSecretId() { return targetPasswordSecretId; }
    public void setTargetPasswordSecretId(String targetPasswordSecretId) { this.targetPasswordSecretId = targetPasswordSecretId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public List<WorkflowStage> getStages() { return stages; }
    public void setStages(List<WorkflowStage> stages) { this.stages = stages == null ? new ArrayList<>() : new ArrayList<>(stages); }

    public List<WorkflowCanvasNode> getCanvasNodes() { return canvasNodes; }
    public void setCanvasNodes(List<WorkflowCanvasNode> canvasNodes) {
        this.canvasNodes = canvasNodes == null ? new ArrayList<>() : new ArrayList<>(canvasNodes);
    }

    public List<WorkflowCanvasEdge> getCanvasEdges() { return canvasEdges; }
    public void setCanvasEdges(List<WorkflowCanvasEdge> canvasEdges) {
        this.canvasEdges = canvasEdges == null ? new ArrayList<>() : new ArrayList<>(canvasEdges);
    }

    public List<FieldMapping> getFieldMappings() { return fieldMappings; }
    public void setFieldMappings(List<FieldMapping> fieldMappings) {
        this.fieldMappings = fieldMappings == null ? new ArrayList<>() : new ArrayList<>(fieldMappings);
    }

    public void addStage(WorkflowStage stage) {
        this.stages.add(stage);
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Workflow workflow)) return false;
        return Objects.equals(id, workflow.id)
                && Objects.equals(name, workflow.name)
                && Objects.equals(owner, workflow.owner)
                && Objects.equals(status, workflow.status)
                && Objects.equals(schedule, workflow.schedule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, owner, status, schedule);
    }
}
