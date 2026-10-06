package com.datasifter.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.datasifter.model.AuditEntry;
import com.datasifter.support.JsonFileStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("json")
public class InMemoryAuditRepository implements AuditRepository {
    private final Path file;
    private final List<AuditEntry> entries;

    @Autowired
    public InMemoryAuditRepository() {
        this(JsonFileStore.resolveDataDirectory().resolve("audit-log.json"));
    }

    InMemoryAuditRepository(Path file) {
        this.file = file;
        this.entries = new CopyOnWriteArrayList<>(JsonFileStore.readList(file, new TypeReference<>() {}));
    }

    @Override
    public synchronized List<AuditEntry> findAll() {
        return new ArrayList<>(entries);
    }

    @Override
    public synchronized AuditEntry save(AuditEntry entry) {
        if (entry.getId() == null || entry.getId().isBlank()) {
            entry.setId(UUID.randomUUID().toString());
        }
        if (entry.getTimestamp() == null) {
            entry.setTimestamp(Instant.now());
        }
        entries.add(entry);
        persist();
        return entry;
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        persist();
    }

    private void persist() {
        JsonFileStore.writeList(file, entries);
    }
}
