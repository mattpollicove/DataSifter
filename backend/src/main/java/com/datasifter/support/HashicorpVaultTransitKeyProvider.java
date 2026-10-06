package com.datasifter.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

public final class HashicorpVaultTransitKeyProvider implements VaultKeyProvider {
    private static final int DATA_KEY_BYTES = 32;

    private final URI address;
    private final String mount;
    private final String keyName;
    private final Map<String, String> environment;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HashicorpVaultTransitKeyProvider() {
        this(System.getenv());
    }

    HashicorpVaultTransitKeyProvider(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
        this.address = validateAddress(ProviderSettings.required(environment, "DATASIFTER_VAULT_TRANSIT_ADDRESS"),
                ProviderSettings.enabled(environment, "DATASIFTER_VAULT_TRANSIT_ALLOW_HTTP_LOCAL"));
        this.mount = validateMount(ProviderSettings.optional(environment, "DATASIFTER_VAULT_TRANSIT_MOUNT", "transit"));
        this.keyName = validateKeyName(ProviderSettings.required(environment, "DATASIFTER_VAULT_TRANSIT_KEY"));
        ProviderSettings.required(environment, "DATASIFTER_VAULT_TRANSIT_TOKEN");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String providerId() {
        return "hashicorp-vault-transit";
    }

    @Override
    public String activeKeyReference() {
        try {
            JsonNode key = request("GET", "/keys/" + keyName, null)
                    .path("data")
                    .path("latest_version");
            if (!key.canConvertToInt() || key.asInt() < 1) {
                throw new IllegalStateException("Vault Transit key is missing or has no active version");
            }
            return keyReference();
        } catch (IOException | InterruptedException | GeneralSecurityException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Unable to verify the configured Vault Transit key", e);
        }
    }

    @Override
    public byte[] wrapKey(String keyReference, byte[] dataKey) throws GeneralSecurityException {
        if (!keyReference().equals(keyReference) || dataKey == null || dataKey.length != DATA_KEY_BYTES) {
            throw new GeneralSecurityException("Vault Transit received an invalid key reference or data key");
        }
        try {
            JsonNode response = request(
                    "POST",
                    "/encrypt/" + keyName,
                    objectMapper.createObjectNode().put("plaintext", Base64.getEncoder().encodeToString(dataKey)));
            String ciphertext = response.path("data").path("ciphertext").asText();
            if (ciphertext.isBlank()) {
                throw new GeneralSecurityException("Vault Transit returned no wrapped data key");
            }
            return ciphertext.getBytes(StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GeneralSecurityException("Unable to wrap the data key with Vault Transit", e);
        }
    }

    @Override
    public byte[] unwrapKey(String keyReference, byte[] wrappedKey) throws GeneralSecurityException {
        if (!keyReference().equals(keyReference) || wrappedKey == null || wrappedKey.length == 0) {
            throw new GeneralSecurityException("Vault Transit received an invalid key reference or wrapped key");
        }
        try {
            JsonNode response = request(
                    "POST",
                    "/decrypt/" + keyName,
                    objectMapper.createObjectNode().put(
                            "ciphertext", new String(wrappedKey, StandardCharsets.UTF_8)));
            String plaintext = response.path("data").path("plaintext").asText();
            if (plaintext.isBlank()) {
                throw new GeneralSecurityException("Vault Transit returned no unwrapped data key");
            }
            return Base64.getDecoder().decode(plaintext);
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GeneralSecurityException("Unable to unwrap the data key with Vault Transit", e);
        }
    }

    private JsonNode request(String method, String operation, JsonNode body)
            throws IOException, InterruptedException, GeneralSecurityException {
        String token = ProviderSettings.required(environment, "DATASIFTER_VAULT_TRANSIT_TOKEN");
        HttpRequest.Builder request = HttpRequest.newBuilder(address.resolve("/v1/" + mount + operation))
                .timeout(Duration.ofSeconds(15))
                .header("X-Vault-Token", token)
                .header("Accept", "application/json");
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        }
        HttpResponse<String> response = httpClient.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GeneralSecurityException("Vault Transit request failed with HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private String keyReference() {
        return mount + "/" + keyName;
    }

    private static URI validateAddress(String address, boolean allowLocalHttp) {
        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Vault Transit address is invalid", e);
        }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean localHttp = "http".equalsIgnoreCase(uri.getScheme())
                && allowLocalHttp
                && uri.getHost() != null
                && (uri.getHost().equalsIgnoreCase("localhost")
                        || uri.getHost().equals("127.0.0.1")
                        || uri.getHost().equals("::1"));
        if ((!secure && !localHttp)
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
            throw new IllegalStateException("Vault Transit requires a valid HTTPS address");
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + "/");
    }

    private static String validateMount(String mount) {
        if (!mount.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) {
            throw new IllegalStateException("Vault Transit mount path is invalid");
        }
        return mount;
    }

    private static String validateKeyName(String keyName) {
        if (!keyName.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalStateException("Vault Transit key name is invalid");
        }
        return keyName;
    }
}
