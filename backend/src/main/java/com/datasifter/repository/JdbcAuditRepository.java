package com.datasifter.repository;

import com.datasifter.model.AuditEntry;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!json")
public class JdbcAuditRepository implements AuditRepository {
    private final JdbcJsonTable<AuditEntry> table;

    public JdbcAuditRepository(JdbcTemplate jdbcTemplate) {
        this.table = new JdbcJsonTable<>(jdbcTemplate, "audit_entries", AuditEntry.class);
    }

    @Override
    public List<AuditEntry> findAll() {
        return table.findAll().stream()
                .sorted(Comparator.comparing(AuditEntry::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public AuditEntry save(AuditEntry entry) {
        if (entry.getId() == null || entry.getId().isBlank()) {
            entry.setId(UUID.randomUUID().toString());
        }
        if (entry.getTimestamp() == null) {
            entry.setTimestamp(Instant.now());
        }
        table.save(entry.getId(), entry);
        return entry;
    }

    @Override
    public void clear() {
        table.clear();
    }
}
