package com.datasifter.model;

import java.time.Instant;
import java.util.Objects;

public class AuditEntry {
    private String id;
    private Instant timestamp;
    private String actor;
    private String event;
    private String details;

    public AuditEntry() {
    }

    public AuditEntry(String id, Instant timestamp, String actor, String event, String details) {
        this.id = id;
        this.timestamp = timestamp;
        this.actor = actor;
        this.event = event;
        this.details = details;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AuditEntry entry)) return false;
        return Objects.equals(id, entry.id)
                && Objects.equals(actor, entry.actor)
                && Objects.equals(event, entry.event);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, actor, event);
    }
}
