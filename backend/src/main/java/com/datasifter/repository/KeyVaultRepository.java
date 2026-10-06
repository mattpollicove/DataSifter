package com.datasifter.repository;

import com.datasifter.model.KeyVaultSecret;
import java.util.List;
import java.util.Optional;

public interface KeyVaultRepository {
    List<KeyVaultSecret> findAll();
    Optional<KeyVaultSecret> findById(String id);
    KeyVaultSecret save(KeyVaultSecret secret);
    void deleteById(String id);
}
