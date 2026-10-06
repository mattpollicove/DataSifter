package com.datasifter.model;

import java.util.Objects;

public class WorkflowStage {
    private String name;
    private String status;
    private String duration;
    private String records;

    public WorkflowStage() {
    }

    public WorkflowStage(String name, String status, String duration, String records) {
        this.name = name;
        this.status = status;
        this.duration = duration;
        this.records = records;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public String getRecords() { return records; }
    public void setRecords(String records) { this.records = records; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WorkflowStage stage)) return false;
        return Objects.equals(name, stage.name)
                && Objects.equals(status, stage.status)
                && Objects.equals(duration, stage.duration)
                && Objects.equals(records, stage.records);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, status, duration, records);
    }
}
