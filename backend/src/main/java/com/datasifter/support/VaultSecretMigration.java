package com.datasifter.support;

import com.datasifter.model.KeyVaultSecret;
import com.datasifter.repository.KeyVaultRepository;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class VaultSecretMigration implements ApplicationRunner {
    private final KeyVaultRepository repository;

    public VaultSecretMigration(KeyVaultRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<KeyVaultSecret> secrets = repository.findAll();
        secrets.forEach(repository::save);
    }
}
