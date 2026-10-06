package com.datasifter.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.datasifter.model.Connector;
import com.datasifter.support.JsonFileStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("json")
public class InMemoryConnectorRepository implements ConnectorRepository {
    private final Path file;
    private final Map<String, Connector> connectors = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryConnectorRepository() {
        this(JsonFileStore.resolveDataDirectory().resolve("connectors.json"));
    }

    InMemoryConnectorRepository(Path file) {
        this.file = file;
        for (Connector connector : JsonFileStore.readList(file, new TypeReference<List<Connector>>() {})) {
            connectors.put(connector.getId(), connector);
        }
    }

    @Override
    public synchronized List<Connector> findAll() {
        return new ArrayList<>(connectors.values());
    }

    @Override
    public synchronized Optional<Connector> findById(String id) {
        return Optional.ofNullable(connectors.get(id));
    }

    @Override
    public synchronized Connector save(Connector connector) {
        if (connector.getId() == null || connector.getId().isBlank()) {
            connector.setId(java.util.UUID.randomUUID().toString());
        }
        connector.setUpdatedAt(java.time.Instant.now());
        connectors.put(connector.getId(), connector);
        persist();
        return connector;
    }

    @Override
    public synchronized void deleteById(String id) {
        connectors.remove(id);
        persist();
    }

    private void persist() {
        JsonFileStore.writeList(file, new ArrayList<>(connectors.values()));
    }
}
