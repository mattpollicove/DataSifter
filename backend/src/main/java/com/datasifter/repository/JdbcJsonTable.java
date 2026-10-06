package com.datasifter.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcJsonTable<T> {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final Class<T> entityType;

    JdbcJsonTable(JdbcTemplate jdbcTemplate, String tableName, Class<T> entityType) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
        this.entityType = entityType;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    List<T> findAll() {
        return jdbcTemplate.query(
                "SELECT payload_json FROM " + tableName + " ORDER BY id",
                (resultSet, rowNumber) -> deserialize(resultSet.getString("payload_json")));
    }

    Optional<T> findById(String id) {
        List<T> results = jdbcTemplate.query(
                "SELECT payload_json FROM " + tableName + " WHERE id = ?",
                (resultSet, rowNumber) -> deserialize(resultSet.getString("payload_json")),
                id);
        return results.stream().findFirst();
    }

    void save(String id, T value) {
        String serialized = serialize(value);
        int updated = jdbcTemplate.update(
                "UPDATE " + tableName + " SET payload_json = ? WHERE id = ?",
                serialized,
                id);
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO " + tableName + " (id, payload_json) VALUES (?, ?)",
                        id,
                        serialized);
            } catch (DuplicateKeyException concurrentInsert) {
                jdbcTemplate.update(
                        "UPDATE " + tableName + " SET payload_json = ? WHERE id = ?",
                        serialized,
                        id);
            }
        }
    }

    void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM " + tableName + " WHERE id = ?", id);
    }

    void clear() {
        jdbcTemplate.update("DELETE FROM " + tableName);
    }

    private String serialize(T value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize " + entityType.getSimpleName() + " for database storage", e);
        }
    }

    private T deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, entityType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize " + entityType.getSimpleName() + " from database storage", e);
        }
    }
}
