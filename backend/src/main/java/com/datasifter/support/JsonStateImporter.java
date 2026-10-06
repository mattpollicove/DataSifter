package com.datasifter.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.datasifter.model.AuditEntry;
import com.datasifter.model.Connector;
import com.datasifter.model.JobExecution;
import com.datasifter.model.KeyVaultSecret;
import com.datasifter.model.Workflow;
import com.datasifter.repository.AuditRepository;
import com.datasifter.repository.ConnectorRepository;
import com.datasifter.repository.JobExecutionRepository;
import com.datasifter.repository.KeyVaultRepository;
import com.datasifter.repository.WorkflowRepository;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!json")
@ConditionalOnProperty(name = "datasifter.persistence.import-json-on-startup", havingValue = "true")
public class JsonStateImporter implements ApplicationRunner {
    private static final String IMPORT_MARKER = "json_state_import_v1";

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowRepository workflowRepository;
    private final ConnectorRepository connectorRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final AuditRepository auditRepository;
    private final KeyVaultRepository keyVaultRepository;

    public JsonStateImporter(
            JdbcTemplate jdbcTemplate,
            WorkflowRepository workflowRepository,
            ConnectorRepository connectorRepository,
            JobExecutionRepository jobExecutionRepository,
            AuditRepository auditRepository,
            KeyVaultRepository keyVaultRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.workflowRepository = workflowRepository;
        this.connectorRepository = connectorRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.auditRepository = auditRepository;
        this.keyVaultRepository = keyVaultRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (isImportComplete()) {
            return;
        }
        requireEmptyDatabaseTables();

        Path dataDirectory = JsonFileStore.resolveDataDirectory();
        List<Workflow> workflows = JsonFileStore.readList(
                dataDirectory.resolve("workflows.json"), new TypeReference<>() {});
        List<Connector> connectors = JsonFileStore.readList(
                dataDirectory.resolve("connectors.json"), new TypeReference<>() {});
        List<JobExecution> jobs = JsonFileStore.readList(
                dataDirectory.resolve("job-executions.json"), new TypeReference<>() {});
        List<AuditEntry> auditEntries = JsonFileStore.readList(
                dataDirectory.resolve("audit-log.json"), new TypeReference<>() {});
        List<KeyVaultSecret> secrets = JsonFileStore.readList(
                dataDirectory.resolve("keyvault.json"), new TypeReference<>() {});

        workflows.forEach(workflowRepository::save);
        connectors.forEach(connectorRepository::save);
        jobs.forEach(jobExecutionRepository::save);
        auditEntries.forEach(auditRepository::save);
        secrets.stream().map(this::decryptLegacySecret).forEach(keyVaultRepository::save);

        jdbcTemplate.update(
                "INSERT INTO datasifter_metadata (meta_key, meta_value) VALUES (?, ?)",
                IMPORT_MARKER,
                "complete");
    }

    private boolean isImportComplete() {
        return !jdbcTemplate.query(
                "SELECT meta_value FROM datasifter_metadata WHERE meta_key = ?",
                (resultSet, rowNumber) -> resultSet.getString(1),
                IMPORT_MARKER).isEmpty();
    }

    private void requireEmptyDatabaseTables() {
        int rowCount = jdbcTemplate.queryForObject(
                "SELECT (SELECT COUNT(*) FROM workflows) "
                        + "+ (SELECT COUNT(*) FROM connectors) "
                        + "+ (SELECT COUNT(*) FROM job_executions) "
                        + "+ (SELECT COUNT(*) FROM audit_entries) "
                        + "+ (SELECT COUNT(*) FROM key_vault_secrets)",
                Integer.class);
        if (rowCount != 0) {
            throw new IllegalStateException(
                    "Cannot import legacy JSON state because the database already contains control-plane records");
        }
    }

    private KeyVaultSecret decryptLegacySecret(KeyVaultSecret encrypted) {
        KeyVaultSecret secret = new KeyVaultSecret(
                encrypted.getId(),
                encrypted.getName(),
                encrypted.getType(),
                encrypted.getStatus(),
                VaultCrypto.decrypt(encrypted.getValue()));
        secret.setCreatedAt(encrypted.getCreatedAt());
        secret.setUpdatedAt(encrypted.getUpdatedAt());
        return secret;
    }
}
