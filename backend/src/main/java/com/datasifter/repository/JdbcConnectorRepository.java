package com.datasifter.repository;

import com.datasifter.model.Connector;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!json")
public class JdbcConnectorRepository implements ConnectorRepository {
    private final JdbcJsonTable<Connector> table;

    public JdbcConnectorRepository(JdbcTemplate jdbcTemplate) {
        this.table = new JdbcJsonTable<>(jdbcTemplate, "connectors", Connector.class);
    }

    @Override
    public List<Connector> findAll() {
        return table.findAll();
    }

    @Override
    public Optional<Connector> findById(String id) {
        return table.findById(id);
    }

    @Override
    public Connector save(Connector connector) {
        if (connector.getId() == null || connector.getId().isBlank()) {
            connector.setId(UUID.randomUUID().toString());
        }
        if (connector.getCreatedAt() == null) {
            connector.setCreatedAt(Instant.now());
        }
        connector.setUpdatedAt(Instant.now());
        table.save(connector.getId(), connector);
        return connector;
    }

    @Override
    public void deleteById(String id) {
        table.deleteById(id);
    }
}
