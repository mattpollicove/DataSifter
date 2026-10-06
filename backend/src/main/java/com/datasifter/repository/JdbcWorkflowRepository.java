package com.datasifter.repository;

import com.datasifter.model.Workflow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!json")
public class JdbcWorkflowRepository implements WorkflowRepository {
    private final JdbcJsonTable<Workflow> table;

    public JdbcWorkflowRepository(JdbcTemplate jdbcTemplate) {
        this.table = new JdbcJsonTable<>(jdbcTemplate, "workflows", Workflow.class);
    }

    @Override
    public List<Workflow> findAll() {
        return table.findAll();
    }

    @Override
    public Optional<Workflow> findById(String id) {
        return table.findById(id);
    }

    @Override
    public Workflow save(Workflow workflow) {
        if (workflow.getId() == null || workflow.getId().isBlank()) {
            workflow.setId(UUID.randomUUID().toString());
        }
        if (workflow.getCreatedAt() == null) {
            workflow.setCreatedAt(Instant.now());
        }
        workflow.setUpdatedAt(Instant.now());
        table.save(workflow.getId(), workflow);
        return workflow;
    }

    @Override
    public void deleteById(String id) {
        table.deleteById(id);
    }
}
