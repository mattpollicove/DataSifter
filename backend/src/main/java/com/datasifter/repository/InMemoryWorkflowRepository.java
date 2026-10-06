package com.datasifter.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.datasifter.model.Workflow;
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
public class InMemoryWorkflowRepository implements WorkflowRepository {
    private final Path file;
    private final Map<String, Workflow> workflows = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryWorkflowRepository() {
        this(JsonFileStore.resolveDataDirectory().resolve("workflows.json"));
    }

    InMemoryWorkflowRepository(Path file) {
        this.file = file;
        for (Workflow workflow : JsonFileStore.readList(file, new TypeReference<List<Workflow>>() {})) {
            workflows.put(workflow.getId(), workflow);
        }
    }

    @Override
    public synchronized List<Workflow> findAll() {
        return new ArrayList<>(workflows.values());
    }

    @Override
    public synchronized Optional<Workflow> findById(String id) {
        return Optional.ofNullable(workflows.get(id));
    }

    @Override
    public synchronized Workflow save(Workflow workflow) {
        if (workflow.getId() == null || workflow.getId().isBlank()) {
            workflow.setId(java.util.UUID.randomUUID().toString());
        }
        workflow.setUpdatedAt(java.time.Instant.now());
        workflows.put(workflow.getId(), workflow);
        persist();
        return workflow;
    }

    @Override
    public synchronized void deleteById(String id) {
        workflows.remove(id);
        persist();
    }

    private void persist() {
        JsonFileStore.writeList(file, new ArrayList<>(workflows.values()));
    }
}
