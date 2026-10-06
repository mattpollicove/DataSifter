package com.datasifter.service;

import com.datasifter.model.Connector;
import com.datasifter.repository.ConnectorRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ConnectorService {
    private final ConnectorRepository repository;

    public ConnectorService(ConnectorRepository repository) {
        this.repository = repository;
    }

    public List<Connector> getAllConnectors() {
        return repository.findAll();
    }

    public Optional<Connector> getConnectorById(String id) {
        return repository.findById(id);
    }

    public Connector createConnector(Connector connector) {
        if (connector == null) {
            throw new IllegalArgumentException("Connector cannot be null");
        }
        if (connector.getHost() == null || connector.getHost().isBlank()) {
            connector.setHost(defaultHostForType(connector.getType()));
        }
        if (connector.getRequiresTls() == null) {
            connector.setRequiresTls(true);
        }
        connector.setPolicy(validatePolicy(connector));
        return repository.save(connector);
    }

    public Connector updateConnector(String id, Connector updatedConnector) {
        Connector existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found: " + id));

        existing.setName(updatedConnector.getName());
        existing.setType(updatedConnector.getType());
        existing.setStatus(updatedConnector.getStatus());
        existing.setProtocol(updatedConnector.getProtocol());
        existing.setHost(updatedConnector.getHost() == null || updatedConnector.getHost().isBlank() ? defaultHostForType(updatedConnector.getType()) : updatedConnector.getHost());
        existing.setPort(updatedConnector.getPort() == null ? defaultPortForType(updatedConnector.getType()) : updatedConnector.getPort());
        existing.setRequiresTls(updatedConnector.getRequiresTls() == null ? true : updatedConnector.getRequiresTls());
        existing.setCertificateThumbprint(updatedConnector.getCertificateThumbprint());
        existing.setLastSync(updatedConnector.getLastSync());
        existing.setPolicy(validatePolicy(updatedConnector));
        return repository.save(existing);
    }

    public String validatePolicy(Connector connector) {
        if (connector == null) {
            return "policy-not-configured";
        }
        String type = connector.getType() == null ? "" : connector.getType().toUpperCase();
        String protocol = connector.getProtocol() == null ? "" : connector.getProtocol().toUpperCase();
        String policy = connector.getPolicy();
        String host = connector.getHost() == null ? "" : connector.getHost().trim();
        boolean requiresTls = Boolean.TRUE.equals(connector.getRequiresTls());

        if (host.isBlank()) {
            return "Connector host required";
        }

        if ("LDAP".equals(type) && (policy == null || !policy.toLowerCase().contains("ldaps")) && (!protocol.contains("LDAPS") && !(protocol.contains("LDAP") && requiresTls))) {
            return "LDAPS required";
        }
        if ("JDBC".equals(type) && (policy == null || !policy.toLowerCase().contains("tls")) && (!protocol.contains("TLS") && !requiresTls)) {
            return "Mutual TLS";
        }
        if ("FILE".equals(type) && (policy == null || !policy.toLowerCase().contains("key"))) {
            return "Private key rotation";
        }
        if (requiresTls && (policy == null || !policy.toLowerCase().contains("tls")) && !"FILE".equals(type)) {
            return "TLS required";
        }
        return policy == null ? "policy-not-configured" : policy;
    }

    private String defaultHostForType(String type) {
        if (type == null) {
            return "localhost";
        }
        return switch (type.toUpperCase()) {
            case "LDAP" -> "ldap.internal.local";
            case "JDBC" -> "db.internal.local";
            case "FILE" -> "files.internal.local";
            default -> "localhost";
        };
    }

    private int defaultPortForType(String type) {
        if (type == null) {
            return 443;
        }
        return switch (type.toUpperCase()) {
            case "LDAP" -> 636;
            case "JDBC" -> 5432;
            case "FILE" -> 22;
            default -> 443;
        };
    }

    public void deleteConnector(String id) {
        repository.deleteById(id);
    }
}
