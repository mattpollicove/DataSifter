package com.datasifter.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.datasifter.model.KeyVaultSecret;
import com.datasifter.support.JsonFileStore;
import com.datasifter.config.VaultConfiguration;
import com.datasifter.support.VaultCrypto;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("json")
public class InMemoryKeyVaultRepository implements KeyVaultRepository {
    private final Path file;
    private final Map<String, KeyVaultSecret> secrets = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryKeyVaultRepository(VaultConfiguration vaultConfiguration) {
        this(JsonFileStore.resolveDataDirectory().resolve("keyvault.json"));
        java.util.Objects.requireNonNull(vaultConfiguration);
    }

    InMemoryKeyVaultRepository(Path file) {
        this.file = file;
        for (KeyVaultSecret secret : JsonFileStore.readList(file, new TypeReference<List<KeyVaultSecret>>() {})) {
            KeyVaultSecret hydrated = new KeyVaultSecret(
                    secret.getId(),
                    secret.getName(),
                    secret.getType(),
                    secret.getStatus(),
                    VaultCrypto.decrypt(secret.getValue())
            );
            hydrated.setCreatedAt(secret.getCreatedAt());
            hydrated.setUpdatedAt(secret.getUpdatedAt());
            secrets.put(hydrated.getId(), hydrated);
        }
        if (!secrets.isEmpty()) {
            persist();
        }
    }

    @Override
    public synchronized List<KeyVaultSecret> findAll() {
        return secrets.values().stream()
                .map(this::cloneSecret)
                .toList();
    }

    @Override
    public synchronized Optional<KeyVaultSecret> findById(String id) {
        return Optional.ofNullable(secrets.get(id)).map(this::cloneSecret);
    }

    @Override
    public synchronized KeyVaultSecret save(KeyVaultSecret secret) {
        if (secret == null) {
            throw new IllegalArgumentException("Secret cannot be null");
        }
        if (secret.getId() == null || secret.getId().isBlank()) {
            secret.setId(UUID.randomUUID().toString());
        }
        if (secret.getCreatedAt() == null) {
            secret.setCreatedAt(java.time.Instant.now());
        }
        secret.setUpdatedAt(java.time.Instant.now());
        secrets.put(secret.getId(), cloneSecret(secret));
        persist();
        return cloneSecret(secret);
    }

    @Override
    public synchronized void deleteById(String id) {
        secrets.remove(id);
        persist();
    }

    private void persist() {
        List<KeyVaultSecret> persistable = new ArrayList<>();
        for (KeyVaultSecret secret : secrets.values()) {
            KeyVaultSecret copy = cloneSecret(secret);
            copy.setValue(VaultCrypto.encrypt(copy.getValue()));
            persistable.add(copy);
        }
        JsonFileStore.writeList(file, persistable);
    }

    private KeyVaultSecret cloneSecret(KeyVaultSecret secret) {
        KeyVaultSecret copy = new KeyVaultSecret(
                secret.getId(),
                secret.getName(),
                secret.getType(),
                secret.getStatus(),
                secret.getValue()
        );
        copy.setCreatedAt(secret.getCreatedAt());
        copy.setUpdatedAt(secret.getUpdatedAt());
        return copy;
    }
}
