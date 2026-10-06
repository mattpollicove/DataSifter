package com.datasifter.service;

import com.datasifter.model.FieldMapping;
import com.datasifter.model.JobExecution;
import com.datasifter.model.KeyVaultSecret;
import com.datasifter.model.Workflow;
import com.datasifter.support.VaultCrypto;
import com.datasifter.support.LocalWorkflowFileStore;
import com.datasifter.support.WorkflowFileStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CsvJdbcJobProcessor {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> PRIVACY_ACTIONS = Set.of("none", "sha256", "mask", "encrypt", "drop");
    private static final Set<String> TRANSFORMATIONS = Set.of("trim", "lowercase", "uppercase");
    private static final int LDAP_PAGE_SIZE = 250;
    private static final int JDBC_FETCH_SIZE = 500;
    private static final long ESTIMATED_BYTES_PER_ROW = 16 * 1024L;

    private final WorkflowService workflowService;
    private final KeyVaultService keyVaultService;
    private final JobExecutionService jobExecutionService;
    private final WorkflowFileStore fileStore;

    @Autowired
    public CsvJdbcJobProcessor(
            WorkflowService workflowService,
            KeyVaultService keyVaultService,
            JobExecutionService jobExecutionService,
            WorkflowFileStore fileStore) {
        this.workflowService = workflowService;
        this.keyVaultService = keyVaultService;
        this.jobExecutionService = jobExecutionService;
        this.fileStore = fileStore;
    }

    public CsvJdbcJobProcessor(
            WorkflowService workflowService,
            KeyVaultService keyVaultService,
            JobExecutionService jobExecutionService) {
        this(workflowService, keyVaultService, jobExecutionService, new LocalWorkflowFileStore(""));
    }

    public int process(JobExecution job, String workerId) throws Exception {
        Workflow workflow = workflowService.getWorkflowById(job.getWorkflowId())
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + job.getWorkflowId()));
        List<FieldMapping> mappings = validateMappings(workflow.getFieldMappings());
        String sourceType = normalized(workflow.getSourceType(), "csv");
        String targetUrl = validateJdbcUrl(workflow.getTargetJdbcUrl(), "Target JDBC URL");
        String targetTable = quoteTable(workflow.getTargetTable(), targetUrl);
        String targetUsername = required(workflow.getTargetUsername(), "Target username");
        String targetPassword = secretValue(workflow.getTargetPasswordSecretId(), "Target password secret");
        String insertSql = insertSql(targetTable, mappings, targetUrl);

        try (Connection target = DriverManager.getConnection(targetUrl, targetUsername, targetPassword)) {
            boolean previousAutoCommit = target.getAutoCommit();
            target.setAutoCommit(false);
            try {
                int written;
                try (PreparedStatement insert = target.prepareStatement(insertSql)) {
                    AdaptiveBatchLimits batchLimits = adaptiveBatchLimits();
                    RowWriter writer = new RowWriter(insert, mappings, job, workerId, batchLimits);
                    switch (sourceType) {
                        case "csv" -> readCsv(workflow.getSourceCsvPath(), mappings, writer);
                        case "jdbc" -> readJdbc(workflow, writer);
                        case "ldap", "ldaps" -> readLdap(workflow, mappings, writer);
                        default -> throw new IllegalArgumentException("Unsupported workflow source type: " + sourceType);
                    }
                    written = writer.finish();
                }
                target.commit();
                return written;
            } catch (Exception e) {
                try {
                    target.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                target.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private void readCsv(String sourceReference, List<FieldMapping> mappings, RowWriter writer) throws Exception {
        String reference = required(sourceReference, "Workflow CSV input");
        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(fileStore.open(reference), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .get()
                     .parse(reader)) {
            Map<String, Integer> headers = parser.getHeaderMap();
            for (FieldMapping mapping : mappings) {
                if (!"drop".equals(normalized(mapping.getPrivacyAction(), "none"))
                        && !headers.containsKey(mapping.getSourceField())) {
                    throw new IllegalArgumentException("CSV is missing mapped source field: " + mapping.getSourceField());
                }
            }
            for (CSVRecord record : parser) {
                Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (String header : headers.keySet()) {
                    values.put(header, record.get(header));
                }
                writer.write(values);
            }
        }
    }

    private void readJdbc(Workflow workflow, RowWriter writer) throws Exception {
        String query = required(workflow.getSourceQuery(), "Source SQL query").trim();
        if (!query.matches("(?is)^select\\b.*")
                || query.indexOf(';') >= 0
                || query.contains("--")
                || query.contains("/*")
                || query.matches("(?is).*\\b(for\\s+update|into\\s+(outfile|dumpfile))\\b.*")) {
            throw new IllegalArgumentException("Source SQL must be a single read-only SELECT query");
        }
        String url = validateJdbcUrl(workflow.getSourceJdbcUrl(), "Source JDBC URL");
        String username = required(workflow.getSourceUsername(), "Source username");
        String password = secretValue(workflow.getSourcePasswordSecretId(), "Source password secret");
        try (Connection source = DriverManager.getConnection(url, username, password)) {
            source.setReadOnly(true);
            boolean previousAutoCommit = source.getAutoCommit();
            source.setAutoCommit(false);
            try {
                try (Statement statement = source.createStatement(
                             ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    statement.setFetchSize(JDBC_FETCH_SIZE);
                    try (ResultSet results = statement.executeQuery(query)) {
                        var columns = results.getMetaData();
                        while (results.next()) {
                            Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                            for (int index = 1; index <= columns.getColumnCount(); index++) {
                                Object value = results.getObject(index);
                                values.put(columns.getColumnLabel(index), value == null ? "" : value.toString());
                            }
                            writer.write(values);
                        }
                    }
                }
                source.commit();
            } catch (Exception e) {
                try {
                    source.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                source.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private void readLdap(Workflow workflow, List<FieldMapping> mappings, RowWriter writer) throws Exception {
        String url = required(workflow.getSourceLdapUrl(), "LDAP source URL");
        URI ldapUri;
        try {
            ldapUri = URI.create(url);
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException("LDAP source URL is invalid", invalidUri);
        }
        if (!"ldaps".equalsIgnoreCase(ldapUri.getScheme())
                || ldapUri.getHost() == null
                || ldapUri.getRawUserInfo() != null
                || ldapUri.getPort() != -1 && (ldapUri.getPort() < 1 || ldapUri.getPort() > 65535)) {
            throw new IllegalArgumentException("LDAP sources require LDAPS");
        }
        String baseDn = required(workflow.getSourceLdapBaseDn(), "LDAP base DN");
        String filter = required(workflow.getSourceLdapFilter(), "LDAP search filter");
        String bindDn = workflow.getSourceLdapBindDn();
        String passwordSecretId = workflow.getSourceLdapPasswordSecretId();
        String bindPassword = bindDn == null || bindDn.isBlank()
                ? null
                : secretValue(passwordSecretId, "LDAP bind password secret");
        List<String> requestedAttributes = workflow.getSourceLdapAttributes() == null
                ? new ArrayList<>()
                : new ArrayList<>(workflow.getSourceLdapAttributes());
        if (requestedAttributes.isEmpty()) {
            mappings.stream()
                    .filter(mapping -> !"drop".equals(normalized(mapping.getPrivacyAction(), "none")))
                    .map(FieldMapping::getSourceField)
                    .filter(attribute -> !"dn".equalsIgnoreCase(attribute))
                    .distinct()
                    .forEach(requestedAttributes::add);
        }
        String[] attributes = requestedAttributes.isEmpty() ? null : requestedAttributes.toArray(String[]::new);
        java.util.Hashtable<String, String> environment = new java.util.Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        environment.put(Context.PROVIDER_URL, url);
        environment.put(Context.REFERRAL, "throw");
        environment.put("com.sun.jndi.ldap.connect.timeout", "10000");
        environment.put("com.sun.jndi.ldap.read.timeout", "30000");
        if (bindDn != null && !bindDn.isBlank()) {
            environment.put(Context.SECURITY_AUTHENTICATION, "simple");
            environment.put(Context.SECURITY_PRINCIPAL, bindDn);
            environment.put(Context.SECURITY_CREDENTIALS, bindPassword);
        }

        LdapContext context = new InitialLdapContext(environment, null);
        try {
            context.setRequestControls(new Control[] {new PagedResultsControl(LDAP_PAGE_SIZE, Control.CRITICAL)});
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(attributes);
            byte[] cookie = null;
            Set<String> previousCookies = new HashSet<>();
            do {
                NamingEnumeration<SearchResult> results = context.search(baseDn, filter, controls);
                try {
                    while (results.hasMore()) {
                        SearchResult result = results.next();
                        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                        values.put("dn", result.getNameInNamespace());
                        Attributes entryAttributes = result.getAttributes();
                        NamingEnumeration<? extends Attribute> allAttributes = entryAttributes.getAll();
                        try {
                            while (allAttributes.hasMore()) {
                                Attribute attribute = allAttributes.next();
                                NamingEnumeration<?> entries = attribute.getAll();
                                List<String> items = new ArrayList<>();
                                try {
                                    while (entries.hasMore()) {
                                        items.add(String.valueOf(entries.next()));
                                    }
                                } finally {
                                    entries.close();
                                }
                                values.put(attribute.getID(), String.join(",", items));
                            }
                        } finally {
                            allAttributes.close();
                        }
                        writer.write(values);
                    }
                } finally {
                    results.close();
                }
                cookie = responseCookie(context.getResponseControls());
                if (cookie != null && cookie.length > 0
                        && !previousCookies.add(Base64.getEncoder().encodeToString(cookie))) {
                    throw new IOException("LDAP server repeated a paging cookie; refusing an unbounded search");
                }
                context.setRequestControls(new Control[] {
                    new PagedResultsControl(LDAP_PAGE_SIZE, cookie, Control.CRITICAL)
                });
            } while (cookie != null && cookie.length > 0);
        } catch (Exception processingFailure) {
            try {
                context.close();
            } catch (Exception closeFailure) {
                processingFailure.addSuppressed(closeFailure);
            }
            throw processingFailure;
        }
        context.close();
    }

    private byte[] responseCookie(Control[] responseControls) throws IOException {
        if (responseControls != null) {
            for (Control control : responseControls) {
                if (control instanceof PagedResultsResponseControl page) {
                    return page.getCookie();
                }
            }
        }
        throw new IOException("LDAP server did not return the requested paged-search response");
    }

    private AdaptiveBatchLimits adaptiveBatchLimits() {
        Runtime runtime = Runtime.getRuntime();
        long available = Math.max(0L, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
        long batchMemoryBudget = Math.max(
                ESTIMATED_BYTES_PER_ROW,
                Math.min(runtime.maxMemory() / 100, available / 20));
        int rowLimit = (int) Math.max(
                1L,
                Math.min(5000L, batchMemoryBudget / ESTIMATED_BYTES_PER_ROW));
        return new AdaptiveBatchLimits(rowLimit, batchMemoryBudget);
    }

    private List<FieldMapping> validateMappings(List<FieldMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("At least one field mapping is required");
        }
        List<FieldMapping> included = new ArrayList<>();
        Set<String> targetFields = new java.util.HashSet<>();
        for (FieldMapping mapping : mappings) {
            if (mapping == null) {
                throw new IllegalArgumentException("Field mapping cannot be null");
            }
            String source = required(mapping.getSourceField(), "Mapping source field");
            String target = required(mapping.getTargetField(), "Mapping target field");
            if (!IDENTIFIER.matcher(target).matches()) {
                throw new IllegalArgumentException("Mapping target field must be a simple SQL identifier");
            }
            mapping.setSourceField(source);
            mapping.setTargetField(target);
            String action = normalized(mapping.getPrivacyAction(), "none");
            if (!PRIVACY_ACTIONS.contains(action)) {
                throw new IllegalArgumentException("Unsupported privacy action: " + action);
            }
            mapping.setPrivacyAction(action);
            List<String> transformations = mapping.getTransformations() == null
                    ? List.of()
                    : mapping.getTransformations().stream()
                            .map(value -> normalized(value, ""))
                            .toList();
            if (!TRANSFORMATIONS.containsAll(transformations)) {
                throw new IllegalArgumentException("Unsupported field transformation");
            }
            mapping.setTransformations(transformations);
            if (!"drop".equals(action)) {
                if (!targetFields.add(target.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate mapped target field: " + target);
                }
                included.add(mapping);
            }
        }
        if (included.isEmpty()) {
            throw new IllegalArgumentException("At least one mapping must write a target field");
        }
        return List.copyOf(mappings);
    }

    private String applyTransforms(String value, FieldMapping mapping) {
        String transformed = value;
        for (String action : mapping.getTransformations()) {
            transformed = switch (action) {
                case "trim" -> transformed.trim();
                case "lowercase" -> transformed.toLowerCase(Locale.ROOT);
                case "uppercase" -> transformed.toUpperCase(Locale.ROOT);
                default -> throw new IllegalArgumentException("Unsupported field transformation: " + action);
            };
        }
        return switch (mapping.getPrivacyAction()) {
            case "none" -> transformed;
            case "mask" -> transformed.isEmpty() ? transformed
                    : "*".repeat(Math.max(0, transformed.length() - 4))
                            + transformed.substring(Math.max(0, transformed.length() - 4));
            case "sha256" -> sha256(transformed);
            case "encrypt" -> VaultCrypto.encrypt(transformed);
            case "drop" -> throw new IllegalArgumentException("Dropped fields cannot be transformed");
            default -> throw new IllegalArgumentException("Unsupported privacy action");
        };
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String insertSql(String table, List<FieldMapping> mappings, String jdbcUrl) {
        List<String> includedTargets = mappings.stream()
                .filter(mapping -> !"drop".equals(mapping.getPrivacyAction()))
                .map(FieldMapping::getTargetField)
                .map(target -> quoteIdentifier(target, jdbcUrl))
                .toList();
        String placeholders = String.join(", ", java.util.Collections.nCopies(includedTargets.size(), "?"));
        return "INSERT INTO " + table + " (" + String.join(", ", includedTargets) + ") VALUES (" + placeholders + ")";
    }

    private String quoteTable(String tableName, String jdbcUrl) {
        String table = required(tableName, "Target table");
        String[] parts = table.split("\\.", -1);
        if (parts.length < 1 || parts.length > 2) {
            throw new IllegalArgumentException("Target table must be table or schema.table");
        }
        for (String part : parts) {
            if (!IDENTIFIER.matcher(part).matches()) {
                throw new IllegalArgumentException("Target table name contains an invalid identifier");
            }
        }
        return java.util.Arrays.stream(parts)
                .map(part -> quoteIdentifier(part, jdbcUrl))
                .collect(java.util.stream.Collectors.joining("."));
    }

    private String quoteIdentifier(String identifier, String jdbcUrl) {
        if (jdbcUrl != null && (jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.startsWith("jdbc:h2:"))) {
            return "`" + identifier + "`";
        }
        return "\"" + identifier + "\"";
    }

    private String validateJdbcUrl(String value, String label) {
        String jdbcUrl = required(value, label);
        String normalized = jdbcUrl.toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("jdbc:mysql://")
                || normalized.startsWith("jdbc:postgresql://")
                || normalized.startsWith("jdbc:h2:mem:"))
                || jdbcUrl.indexOf('@') >= 0
                || jdbcUrl.indexOf('#') >= 0
                || jdbcUrl.matches("(?i).*([?&](user|password|pwd)=).*")) {
            throw new IllegalArgumentException(label + " must be a credential-free MySQL or PostgreSQL JDBC URL");
        }
        if (normalized.startsWith("jdbc:h2:mem:")) {
            return jdbcUrl;
        }
        if (normalized.startsWith("jdbc:mysql://")) {
            if (!jdbcUrl.matches("(?i).*([?&]sslMode=VERIFY_IDENTITY)(&.*)?$")) {
                throw new IllegalArgumentException("MySQL JDBC connections must use sslMode=VERIFY_IDENTITY");
            }
            return appendJdbcOptions(jdbcUrl, Map.of("connectTimeout", "10000", "socketTimeout", "60000"));
        }
        if (!jdbcUrl.matches("(?i).*([?&]sslmode=verify-full)(&.*)?$")) {
            throw new IllegalArgumentException("PostgreSQL JDBC connections must use sslmode=verify-full");
        }
        return appendJdbcOptions(jdbcUrl, Map.of(
                "connectTimeout", "10", "socketTimeout", "60", "tcpKeepAlive", "true"));
    }

    private String appendJdbcOptions(String jdbcUrl, Map<String, String> requiredOptions) {
        StringBuilder result = new StringBuilder(jdbcUrl);
        for (Map.Entry<String, String> option : requiredOptions.entrySet()) {
            if (!jdbcUrl.matches("(?i).*([?&]" + Pattern.quote(option.getKey()) + "=).*")) {
                result.append(jdbcUrl.contains("?") ? '&' : '?')
                        .append(option.getKey()).append('=').append(option.getValue());
            }
        }
        return result.toString();
    }

    private String secretValue(String id, String label) {
        String secretId = required(id, label);
        KeyVaultSecret secret = keyVaultService.getSecretById(secretId)
                .orElseThrow(() -> new IllegalArgumentException(label + " was not found"));
        return secret.getValue();
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private final class RowWriter {
        private final PreparedStatement statement;
        private final List<FieldMapping> mappings;
        private final JobExecution job;
        private final String workerId;
        private final AdaptiveBatchLimits batchLimits;
        private int pending;
        private int inserted;
        private long pendingBytes;

        private RowWriter(
                PreparedStatement statement,
                List<FieldMapping> mappings,
                JobExecution job,
                String workerId,
                AdaptiveBatchLimits batchLimits) {
            this.statement = statement;
            this.mappings = mappings;
            this.job = job;
            this.workerId = workerId;
            this.batchLimits = batchLimits;
        }

        private void write(Map<String, String> values) throws SQLException {
            int parameter = 1;
            for (FieldMapping mapping : mappings) {
                if ("drop".equals(mapping.getPrivacyAction())) {
                    continue;
                }
                String value = values.get(mapping.getSourceField());
                if (value == null) {
                    throw new IllegalArgumentException("Source record is missing mapped field: "
                            + mapping.getSourceField());
                }
                String transformed = applyTransforms(value, mapping);
                statement.setString(parameter++, transformed);
                pendingBytes += (long) transformed.length() * Character.BYTES;
            }
            statement.addBatch();
            pending++;
            if (pending >= batchLimits.rowLimit() || pendingBytes >= batchLimits.byteLimit()) {
                flush();
            }
        }

        private int finish() throws SQLException {
            if (pending > 0) {
                flush();
            }
            return inserted;
        }

        private void flush() throws SQLException {
            int[] results = statement.executeBatch();
            if (results.length != pending) {
                throw new SQLException("JDBC batch returned a different result count than submitted rows");
            }
            for (int result : results) {
                if (result == Statement.EXECUTE_FAILED
                        || result < 0 && result != Statement.SUCCESS_NO_INFO) {
                    throw new SQLException("JDBC batch reported a failed row");
                }
            }
            inserted += pending;
            pending = 0;
            pendingBytes = 0;
            jobExecutionService.heartbeat(job.getId(), workerId, 0, inserted, 0);
        }
    }

    private record AdaptiveBatchLimits(int rowLimit, long byteLimit) {
    }
}
