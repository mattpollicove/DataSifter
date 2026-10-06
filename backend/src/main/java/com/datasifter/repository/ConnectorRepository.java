package com.datasifter.repository;

import com.datasifter.model.Connector;
import java.util.List;
import java.util.Optional;

public interface ConnectorRepository {
    List<Connector> findAll();
    Optional<Connector> findById(String id);
    Connector save(Connector connector);
    void deleteById(String id);
}
