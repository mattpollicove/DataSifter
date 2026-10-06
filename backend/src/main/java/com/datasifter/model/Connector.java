package com.datasifter.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Connector {
    private String id;
    private String name;
    private String type;
    private String status;
    private String protocol;
    private String host;
    private Integer port;
    private Boolean requiresTls;
    private String certificateThumbprint;
    private String lastSync;
    private String policy;
    private Instant createdAt;
    private Instant updatedAt;

    public Connector() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Connector(String id, String name, String type, String status, String protocol, String lastSync, String policy) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.status = status;
        this.protocol = protocol;
        this.lastSync = lastSync;
        this.policy = policy;
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

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public Boolean getRequiresTls() { return requiresTls; }
    public void setRequiresTls(Boolean requiresTls) { this.requiresTls = requiresTls; }

    public String getCertificateThumbprint() { return certificateThumbprint; }
    public void setCertificateThumbprint(String certificateThumbprint) { this.certificateThumbprint = certificateThumbprint; }

    public String getLastSync() { return lastSync; }
    public void setLastSync(String lastSync) { this.lastSync = lastSync; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Connector connector)) return false;
        return Objects.equals(id, connector.id)
                && Objects.equals(name, connector.name)
                && Objects.equals(type, connector.type)
                && Objects.equals(protocol, connector.protocol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, type, protocol);
    }
}
