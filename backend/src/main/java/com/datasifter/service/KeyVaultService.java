package com.datasifter.service;

import com.datasifter.model.KeyVaultSecret;
import com.datasifter.repository.KeyVaultRepository;
import com.datasifter.support.VaultCrypto;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class KeyVaultService {
    private final KeyVaultRepository repository;

    public KeyVaultService(KeyVaultRepository repository) {
        this.repository = repository;
    }

    public synchronized List<KeyVaultSecret> getSecrets() {
        return repository.findAll();
    }

    public synchronized Optional<KeyVaultSecret> getSecretById(String id) {
        return repository.findById(id);
    }

    public synchronized KeyVaultSecret createSecret(KeyVaultSecret secret) {
        if (secret == null) {
            throw new IllegalArgumentException("Secret cannot be null");
        }

        String name = normalizeRequired(secret.getName(), "Secret name");
        String type = normalizeOptional(secret.getType(), "database");
        String status = normalizeOptional(secret.getStatus(), "active");
        String value = normalizeRequired(secret.getValue(), "Secret value");

        secret.setName(name);
        secret.setType(type);
        secret.setStatus(status);
        secret.setValue(value);
        return repository.save(secret);
    }

    public synchronized void rotatePassphrase(String newPassphrase) {
        List<KeyVaultSecret> currentSecrets = repository.findAll();
        VaultCrypto.rotatePassphrase(
                newPassphrase,
                () -> currentSecrets.forEach(repository::save),
                () -> currentSecrets.forEach(repository::save));
    }

    public String maskSecretValue(String value) {
        if (value == null || value.isBlank()) {
            return "********";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return "*".repeat(trimmed.length());
        }
        return "************" + trimmed.substring(trimmed.length() - 4);
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value.trim();
    }

    private String normalizeOptional(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
