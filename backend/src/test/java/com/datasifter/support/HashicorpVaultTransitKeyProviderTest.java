package com.datasifter.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HashicorpVaultTransitKeyProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private HashicorpVaultTransitKeyProvider provider;

    @BeforeEach
    void startVaultStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/transit/keys/datasifter", exchange -> respond(exchange, 200,
                "{\"data\":{\"latest_version\":1}}"));
        server.createContext("/v1/transit/encrypt/datasifter", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            String encoded = request.path("plaintext").asText();
            respond(exchange, 200, objectMapper.createObjectNode()
                    .set("data", objectMapper.createObjectNode().put("ciphertext", "mock:" + encoded))
                    .toString());
        });
        server.createContext("/v1/transit/decrypt/datasifter", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            String encoded = request.path("ciphertext").asText().substring("mock:".length());
            respond(exchange, 200, objectMapper.createObjectNode()
                    .set("data", objectMapper.createObjectNode().put("plaintext", encoded))
                    .toString());
        });
        server.start();
        provider = new HashicorpVaultTransitKeyProvider(Map.of(
                "DATASIFTER_VAULT_TRANSIT_ADDRESS", "http://127.0.0.1:" + server.getAddress().getPort(),
                "DATASIFTER_VAULT_TRANSIT_ALLOW_HTTP_LOCAL", "true",
                "DATASIFTER_VAULT_TRANSIT_KEY", "datasifter",
                "DATASIFTER_VAULT_TRANSIT_TOKEN", "test-token"));
    }

    @AfterEach
    void stopVaultStub() {
        server.stop(0);
    }

    @Test
    void verifiesTransitKeyAndWrapsThenUnwrapsDataKeys() throws Exception {
        byte[] dataKey = new byte[32];
        for (int index = 0; index < dataKey.length; index++) {
            dataKey[index] = (byte) index;
        }

        assertEquals("hashicorp-vault-transit", provider.providerId());
        assertEquals("transit/datasifter", provider.activeKeyReference());
        byte[] wrapped = provider.wrapKey("transit/datasifter", dataKey);
        assertArrayEquals(dataKey, provider.unwrapKey("transit/datasifter", wrapped));
    }

    @Test
    void rejectsNonLocalHttpAndInvalidReferences() {
        assertThrows(IllegalStateException.class, () -> new HashicorpVaultTransitKeyProvider(Map.of(
                "DATASIFTER_VAULT_TRANSIT_ADDRESS", "http://vault.example.internal:8200",
                "DATASIFTER_VAULT_TRANSIT_KEY", "datasifter",
                "DATASIFTER_VAULT_TRANSIT_TOKEN", "test-token")));
        assertThrows(java.security.GeneralSecurityException.class,
                () -> provider.wrapKey("transit/other", new byte[32]));
    }

    private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        assertEquals("test-token", exchange.getRequestHeaders().getFirst("X-Vault-Token"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
