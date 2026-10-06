package com.datasifter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WorkerTokenVerifier {
    private final String configuredToken;

    public WorkerTokenVerifier(@Value("${datasifter.workers.shared-token:}") String configuredToken) {
        this.configuredToken = configuredToken;
    }

    public void requireValid(String suppliedToken) {
        if (configuredToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Worker API is not configured");
        }
        if (suppliedToken == null || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid worker credentials");
        }
    }
}
