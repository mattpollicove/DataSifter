package com.datasifter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.datasifter.model.FieldMapping;
import com.datasifter.model.JobExecution;
import com.datasifter.model.KeyVaultSecret;
import com.datasifter.model.Workflow;
import com.datasifter.support.JsonFileStore;
import com.datasifter.support.VaultCrypto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CsvJdbcJobProcessorTest {
    @Test
    void streamsCsvAppliesPrivacyMappingAndInsertsPreparedRows() throws Exception {
        Path uploads = JsonFileStore.resolveDataDirectory().resolve("uploads");
        Files.createDirectories(uploads);
        Path csv = uploads.resolve("processor-test-" + System.nanoTime() + ".csv");
        String databaseId = java.util.UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:csvtest" + databaseId + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Files.writeString(csv, "email,department\nada@example.com,Engineering\ngrace@example.com,Operations\n");
        try {
            Workflow workflow = new Workflow("wf-csv-test", "Test", "QA", "active", "manual");
            workflow.setSourceCsvPath(csv.toString());
            workflow.setTargetJdbcUrl(jdbcUrl);
            workflow.setTargetTable("records");
            workflow.setTargetUsername("sa");
            workflow.setTargetPasswordSecretId("db-secret");
            FieldMapping email = new FieldMapping();
            email.setSourceField("email");
            email.setTargetField("email_hash");
            email.setPrivacyAction("sha256");
            FieldMapping department = new FieldMapping();
            department.setSourceField("department");
            department.setTargetField("department");
            department.setPrivacyAction("none");
            department.setTransformations(List.of("trim", "uppercase"));
            FieldMapping encryptedEmail = new FieldMapping();
            encryptedEmail.setSourceField("email");
            encryptedEmail.setTargetField("email_encrypted");
            encryptedEmail.setPrivacyAction("encrypt");
            Files.writeString(csv, "email,department\nada@example.com, Engineering  \ngrace@example.com, Operations \n");
            workflow.setFieldMappings(List.of(email, department, encryptedEmail));

            WorkflowService workflowService = Mockito.mock(WorkflowService.class);
            KeyVaultService keyVaultService = Mockito.mock(KeyVaultService.class);
            JobExecutionService jobExecutionService = Mockito.mock(JobExecutionService.class);
            when(workflowService.getWorkflowById("wf-csv-test")).thenReturn(Optional.of(workflow));
            when(keyVaultService.getSecretById("db-secret")).thenReturn(Optional.of(
                    new KeyVaultSecret("db-secret", "db", "database", "active", "")));

            try (var connection = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE records (email_hash VARCHAR(64), department VARCHAR(100), email_encrypted VARCHAR(2048))");
            }
            VaultCrypto.initialize("csv-processor-test-passphrase");

            int inserted = new CsvJdbcJobProcessor(workflowService, keyVaultService, jobExecutionService)
                    .process(new JobExecution("job-csv-test", "test", "wf-csv-test", "running", 0, 0, 0), "worker-test");

            assertEquals(2, inserted);
            try (var connection = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "");
                 var statement = connection.createStatement();
                 var result = statement.executeQuery(
                         "SELECT email_hash, department, email_encrypted FROM records ORDER BY department")) {
                result.next();
                assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest("ada@example.com".getBytes(java.nio.charset.StandardCharsets.UTF_8))), result.getString(1));
                assertEquals("ENGINEERING", result.getString(2));
                assertEquals("ada@example.com", VaultCrypto.decrypt(result.getString(3)));
                result.next();
                assertEquals("OPERATIONS", result.getString(2));
                assertEquals("grace@example.com", VaultCrypto.decrypt(result.getString(3)));
            }
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    @Test
    void streamsJdbcSourceIntoJdbcTargetWithConfiguredTransforms() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        String sourceUrl = "jdbc:h2:mem:source" + suffix + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        String targetUrl = "jdbc:h2:mem:target" + suffix + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(sourceUrl, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE source_records (id INT, first_name VARCHAR(100))");
            statement.execute("INSERT INTO source_records VALUES (1, '  Ada  '), (2, '  Grace  ')");
        }
        try (var connection = DriverManager.getConnection(targetUrl, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE target_records (first_name VARCHAR(100))");
        }

        Workflow workflow = new Workflow("wf-jdbc-source", "JDBC source", "QA", "active", "manual");
        workflow.setSourceType("jdbc");
        workflow.setSourceJdbcUrl(sourceUrl);
        workflow.setSourceQuery("SELECT first_name FROM source_records ORDER BY id");
        workflow.setSourceUsername("sa");
        workflow.setSourcePasswordSecretId("db-secret");
        workflow.setTargetJdbcUrl(targetUrl);
        workflow.setTargetTable("target_records");
        workflow.setTargetUsername("sa");
        workflow.setTargetPasswordSecretId("db-secret");
        FieldMapping firstName = new FieldMapping();
        firstName.setSourceField("first_name");
        firstName.setTargetField("first_name");
        firstName.setTransformations(List.of("trim", "uppercase"));
        workflow.setFieldMappings(List.of(firstName));

        WorkflowService workflowService = Mockito.mock(WorkflowService.class);
        KeyVaultService keyVaultService = Mockito.mock(KeyVaultService.class);
        JobExecutionService jobExecutionService = Mockito.mock(JobExecutionService.class);
        when(workflowService.getWorkflowById("wf-jdbc-source")).thenReturn(java.util.Optional.of(workflow));
        when(keyVaultService.getSecretById("db-secret")).thenReturn(java.util.Optional.of(
                new KeyVaultSecret("db-secret", "db", "database", "active", "")));

        int inserted = new CsvJdbcJobProcessor(workflowService, keyVaultService, jobExecutionService)
                .process(new JobExecution("job-jdbc-source", "test", "wf-jdbc-source", "running", 0, 0, 0), "worker-test");

        assertEquals(2, inserted);
        try (var connection = DriverManager.getConnection(targetUrl, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT first_name FROM target_records ORDER BY first_name")) {
            result.next();
            assertEquals("ADA", result.getString(1));
            result.next();
            assertEquals("GRACE", result.getString(1));
        }
    }

    @Test
    void rejectsPlainLdapWithoutOpeningAnInsecureDirectoryConnection() {
        String jdbcUrl = "jdbc:h2:mem:ldapreject" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Workflow workflow = new Workflow("wf-ldap-source", "LDAP source", "QA", "active", "manual");
        workflow.setSourceType("ldap");
        workflow.setSourceLdapUrl("ldap://directory.example.com:389");
        workflow.setTargetJdbcUrl(jdbcUrl);
        workflow.setTargetTable("records");
        workflow.setTargetUsername("sa");
        workflow.setTargetPasswordSecretId("db-secret");
        FieldMapping mapping = new FieldMapping();
        mapping.setSourceField("cn");
        mapping.setTargetField("common_name");
        workflow.setFieldMappings(List.of(mapping));

        WorkflowService workflowService = Mockito.mock(WorkflowService.class);
        KeyVaultService keyVaultService = Mockito.mock(KeyVaultService.class);
        when(workflowService.getWorkflowById("wf-ldap-source")).thenReturn(java.util.Optional.of(workflow));
        when(keyVaultService.getSecretById("db-secret")).thenReturn(java.util.Optional.of(
                new KeyVaultSecret("db-secret", "db", "database", "active", "")));
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE records (common_name VARCHAR(100))");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThrows(IllegalArgumentException.class, () -> new CsvJdbcJobProcessor(
                workflowService, keyVaultService, Mockito.mock(JobExecutionService.class))
                .process(new JobExecution("job-ldap-source", "test", "wf-ldap-source", "running", 0, 0, 0), "worker-test"));
    }

    @Test
    void rejectsJdbcSourceWithoutServerIdentityVerification() throws Exception {
        String targetUrl = "jdbc:h2:mem:tlsreject" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(targetUrl, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE records (name VARCHAR(100))");
        }
        Workflow workflow = new Workflow("wf-jdbc-tls", "JDBC TLS", "QA", "active", "manual");
        workflow.setSourceType("jdbc");
        workflow.setSourceJdbcUrl("jdbc:postgresql://database.example.com:5432/source");
        workflow.setSourceQuery("SELECT name FROM records");
        workflow.setSourceUsername("reader");
        workflow.setSourcePasswordSecretId("source-secret");
        workflow.setTargetJdbcUrl(targetUrl);
        workflow.setTargetTable("records");
        workflow.setTargetUsername("sa");
        workflow.setTargetPasswordSecretId("target-secret");
        FieldMapping mapping = new FieldMapping();
        mapping.setSourceField("name");
        mapping.setTargetField("name");
        workflow.setFieldMappings(List.of(mapping));
        WorkflowService workflowService = Mockito.mock(WorkflowService.class);
        KeyVaultService keyVaultService = Mockito.mock(KeyVaultService.class);
        when(workflowService.getWorkflowById("wf-jdbc-tls")).thenReturn(java.util.Optional.of(workflow));
        when(keyVaultService.getSecretById("target-secret")).thenReturn(java.util.Optional.of(
                new KeyVaultSecret("target-secret", "target", "database", "active", "")));
        when(keyVaultService.getSecretById("source-secret")).thenReturn(java.util.Optional.of(
                new KeyVaultSecret("source-secret", "source", "database", "active", "")));

        assertThrows(IllegalArgumentException.class, () -> new CsvJdbcJobProcessor(
                workflowService, keyVaultService, Mockito.mock(JobExecutionService.class))
                .process(new JobExecution("job-jdbc-tls", "test", "wf-jdbc-tls", "running", 0, 0, 0), "worker-test"));
    }

    @Test
    void rollsBackTargetRowsWhenAnyBatchedInsertFails() throws Exception {
        Path uploads = JsonFileStore.resolveDataDirectory().resolve("uploads");
        Files.createDirectories(uploads);
        Path csv = uploads.resolve("rollback-test-" + System.nanoTime() + ".csv");
        String targetUrl = "jdbc:h2:mem:rollback" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Files.writeString(csv, "name\nok\nvalue-too-long\n");
        try (var connection = DriverManager.getConnection(targetUrl, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE records (name VARCHAR(2))");
        }

        Workflow workflow = new Workflow("wf-batch-rollback", "Rollback", "QA", "active", "manual");
        workflow.setSourceCsvPath(csv.toString());
        workflow.setTargetJdbcUrl(targetUrl);
        workflow.setTargetTable("records");
        workflow.setTargetUsername("sa");
        workflow.setTargetPasswordSecretId("db-secret");
        FieldMapping mapping = new FieldMapping();
        mapping.setSourceField("name");
        mapping.setTargetField("name");
        workflow.setFieldMappings(List.of(mapping));
        WorkflowService workflowService = Mockito.mock(WorkflowService.class);
        KeyVaultService keyVaultService = Mockito.mock(KeyVaultService.class);
        when(workflowService.getWorkflowById("wf-batch-rollback")).thenReturn(java.util.Optional.of(workflow));
        when(keyVaultService.getSecretById("db-secret")).thenReturn(java.util.Optional.of(
                new KeyVaultSecret("db-secret", "db", "database", "active", "")));

        try {
            assertThrows(Exception.class, () -> new CsvJdbcJobProcessor(
                    workflowService, keyVaultService, Mockito.mock(JobExecutionService.class))
                    .process(new JobExecution("job-batch-rollback", "test", "wf-batch-rollback", "running", 0, 0, 0),
                            "worker-test"));
            try (var connection = DriverManager.getConnection(targetUrl, "sa", "");
                 var statement = connection.createStatement();
                 var results = statement.executeQuery("SELECT COUNT(*) FROM records")) {
                results.next();
                assertEquals(0, results.getInt(1));
            }
        } finally {
            Files.deleteIfExists(csv);
        }
    }
}
