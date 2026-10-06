package com.datasifter.repository;

import com.datasifter.model.AuditEntry;
import java.util.List;

public interface AuditRepository {
    List<AuditEntry> findAll();
    AuditEntry save(AuditEntry entry);
    void clear();
}
