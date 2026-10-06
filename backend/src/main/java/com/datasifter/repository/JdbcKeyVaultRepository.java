package com.datasifter.repository;

import com.datasifter.model.KeyVaultSecret;
import com.datasifter.config.VaultConfiguration;
import com.datasifter.support.VaultCrypto;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!json")
public class JdbcKeyVaultRepository implements KeyVaultRepository {
    private final JdbcJsonTable<KeyVaultSecret> table;

    public JdbcKeyVaultRepository(JdbcTemplate jdbcTemplate, VaultConfiguration vaultConfiguration) {
        java.util.Objects.requireNonNull(vaultConfiguration);
        this.table = new JdbcJsonTable<>(jdbcTemplate, "key_vault_secrets", KeyVaultSecret.class);
    }

    @Override
    public List<KeyVaultSecret> findAll() {
        return table.findAll().stream().map(this::decrypt).toList();
    }

    @Override
    public Optional<KeyVaultSecret> findById(String id) {
        return table.findById(id).map(this::decrypt);
    }

    @Override
    public KeyVaultSecret save(KeyVaultSecret secret) {
        if (secret.getId() == null || secret.getId().isBlank()) {
            secret.setId(UUID.randomUUID().toString());
        }
        if (secret.getCreatedAt() == null) {
            secret.setCreatedAt(Instant.now());
        }
        secret.setUpdatedAt(Instant.now());
        KeyVaultSecret encrypted = copy(secret);
        encrypted.setValue(VaultCrypto.encrypt(encrypted.getValue()));
        table.save(encrypted.getId(), encrypted);
        return copy(secret);
    }

    @Override
    public void deleteById(String id) {
        table.deleteById(id);
    }

    private KeyVaultSecret decrypt(KeyVaultSecret encrypted) {
        KeyVaultSecret decrypted = copy(encrypted);
        decrypted.setValue(VaultCrypto.decrypt(decrypted.getValue()));
        return decrypted;
    }

    private KeyVaultSecret copy(KeyVaultSecret secret) {
        KeyVaultSecret copy = new KeyVaultSecret(
                secret.getId(),
                secret.getName(),
                secret.getType(),
                secret.getStatus(),
                secret.getValue());
        copy.setCreatedAt(secret.getCreatedAt());
        copy.setUpdatedAt(secret.getUpdatedAt());
        return copy;
    }
}
