package com.datasifter.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

public class LocalWorkflowFileStore implements WorkflowFileStore {
    private final Path directory;

    public LocalWorkflowFileStore(String configuredDirectory) {
        Path base = configuredDirectory == null || configuredDirectory.isBlank()
                ? JsonFileStore.resolveDataDirectory()
                : Path.of(configuredDirectory);
        this.directory = base.resolve("uploads").toAbsolutePath().normalize();
    }

    @Override
    public String providerId() {
        return "local";
    }

    @Override
    public String store(InputStream content, String fileName) throws IOException {
        String extension = fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".csv") ? ".csv" : ".bin";
        Files.createDirectories(directory);
        String key = UUID.randomUUID() + extension;
        Path destination = directory.resolve(key);
        Path temporary = Files.createTempFile(directory, ".upload-", ".tmp");
        try {
            Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            }
            return "local:" + key;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public InputStream open(String reference) throws IOException {
        return Files.newInputStream(resolve(reference));
    }

    @Override
    public void delete(String reference) throws IOException {
        Files.deleteIfExists(resolve(reference));
    }

    private Path resolve(String reference) throws IOException {
        if (reference == null || reference.isBlank()) {
            throw new IOException("Workflow input reference is missing");
        }
        Path root = directory.toRealPath();
        Path candidate;
        if (!reference.startsWith("local:")) {
            candidate = Path.of(reference).toAbsolutePath().normalize();
            if (!candidate.startsWith(directory)) {
                throw new IOException("Legacy workflow input is outside the managed upload directory");
            }
        } else {
            String key = reference.substring("local:".length());
            candidate = directory.resolve(key).normalize();
            if (!candidate.getParent().equals(directory) || !candidate.startsWith(directory)) {
                throw new IOException("Invalid workflow input reference");
            }
        }
        Path realPath = candidate.toRealPath();
        if (!realPath.startsWith(root) || !Files.isRegularFile(realPath)) {
            throw new IOException("Workflow input must be a regular file in the managed upload directory");
        }
        return realPath;
    }
}
