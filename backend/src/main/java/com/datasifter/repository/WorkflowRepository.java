package com.datasifter.repository;

import com.datasifter.model.Workflow;
import java.util.List;
import java.util.Optional;

public interface WorkflowRepository {
    List<Workflow> findAll();
    Optional<Workflow> findById(String id);
    Workflow save(Workflow workflow);
    void deleteById(String id);
}
