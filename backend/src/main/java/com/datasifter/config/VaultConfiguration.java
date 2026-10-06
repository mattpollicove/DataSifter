package com.datasifter.config;

import com.datasifter.support.VaultCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VaultConfiguration {
    public VaultConfiguration(
            @Value("${datasifter.vault.passphrase:}") String passphrase,
            @Value("${datasifter.vault.provider:local}") String providerId,
            @Value("${datasifter.vault.require-managed-provider:false}") boolean requireManagedProvider) {
        VaultCrypto.initialize(passphrase, providerId, requireManagedProvider);
    }
}
