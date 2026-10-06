package com.datasifter.service;

import com.datasifter.model.AuditEntry;
import com.datasifter.repository.AuditRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    public List<AuditEntry> getAuditLog() {
        return repository.findAll();
    }

    public AuditEntry createAuditEntry(AuditEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Audit entry cannot be null");
        }
        return repository.save(entry);
    }
}
