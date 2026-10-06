package com.datasifter.model;

import java.time.Instant;
import java.util.Objects;

public class KeyVaultSecret {
    private String id;
    private String name;
    private String type;
    private String status;
    private String value;
    private Instant createdAt;
    private Instant updatedAt;

    public KeyVaultSecret() {
    }

    public KeyVaultSecret(String id, String name, String type, String status, String value) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.status = status;
        this.value = value;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof KeyVaultSecret secret)) return false;
        return Objects.equals(id, secret.id)
                && Objects.equals(name, secret.name)
                && Objects.equals(type, secret.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, type);
    }
}
