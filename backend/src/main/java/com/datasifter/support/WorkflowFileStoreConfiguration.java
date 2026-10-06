package com.datasifter.support;

import java.util.ServiceLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class WorkflowFileStoreConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorkflowFileStore.class)
    WorkflowFileStore workflowFileStore(
            @Value("${datasifter.storage.local-directory:}") String configuredDirectory,
            @Value("${datasifter.storage.provider:local}") String providerId,
            @Value("${datasifter.storage.require-distributed-provider:false}") boolean requireDistributedProvider) {
        if ("local".equals(providerId)) {
            if (requireDistributedProvider) {
                throw new IllegalStateException(
                        "A shared durable workflow storage provider is required in this environment");
            }
            return new LocalWorkflowFileStore(configuredDirectory);
        }
        return ServiceLoader.load(WorkflowFileStore.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> providerId.equals(provider.providerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Configured workflow storage provider is not installed: " + providerId));
    }
}
