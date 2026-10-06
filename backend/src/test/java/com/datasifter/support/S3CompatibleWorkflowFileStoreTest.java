package com.datasifter.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

class S3CompatibleWorkflowFileStoreTest {
    private static final String BUCKET = "datasifter-test";
    private static final String OBJECT_BODY = "id,name\n1,Ada\n";

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private HttpServer server;
    private S3Client client;
    private S3CompatibleWorkflowFileStore store;

    @BeforeEach
    void startS3Stub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + BUCKET, this::handleS3Request);
        server.start();
        client = S3Client.builder()
                .endpointOverride(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access", "test-secret")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        store = new S3CompatibleWorkflowFileStore(client, BUCKET);
    }

    @AfterEach
    void stopS3Stub() {
        client.close();
        server.stop(0);
    }

    @Test
    void storesOpensAndDeletesFilesUsingOpaqueReferences() throws Exception {
        String reference = store.store(
                new java.io.ByteArrayInputStream(OBJECT_BODY.getBytes(StandardCharsets.UTF_8)),
                "users.csv",
                OBJECT_BODY.getBytes(StandardCharsets.UTF_8).length);

        assertEquals("s3:", reference.substring(0, 3));
        try (var input = store.open(reference)) {
            assertEquals(OBJECT_BODY, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        store.delete(reference);
        assertThrows(IOException.class, () -> store.open(reference));
    }

    @Test
    void rejectsInvalidLengthsAndReferences() {
        assertThrows(IOException.class, () -> store.store(
                new java.io.ByteArrayInputStream(new byte[] {1}), "input.csv", -1));
        assertThrows(IOException.class, () -> store.open("s3:../outside.csv"));
    }

    private void handleS3Request(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String key = path.substring(("/" + BUCKET + "/").length());
        switch (exchange.getRequestMethod()) {
            case "HEAD" -> exchange.sendResponseHeaders(200, -1);
            case "PUT" -> {
                objects.put(key, decodeSignedChunks(exchange.getRequestBody()));
                exchange.sendResponseHeaders(200, -1);
            }
            case "GET" -> {
                byte[] body = objects.get(key);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                }
            }
            case "DELETE" -> {
                objects.remove(key);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> exchange.sendResponseHeaders(405, -1);
        }
        exchange.close();
    }

    private byte[] decodeSignedChunks(InputStream input) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        while (true) {
            String header = readLine(input);
            int separator = header.indexOf(';');
            int chunkLength = Integer.parseInt(header.substring(0, separator), 16);
            if (chunkLength == 0) {
                while (!readLine(input).isEmpty()) {
                    // Consume the signed trailing checksum headers.
                }
                return payload.toByteArray();
            }
            payload.write(input.readNBytes(chunkLength));
            if (!"\r\n".equals(new String(input.readNBytes(2), StandardCharsets.US_ASCII))) {
                throw new IOException("Malformed signed S3 chunk");
            }
        }
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int current = input.read();
            if (current < 0) {
                throw new IOException("Unexpected end of signed S3 request");
            }
            if (current == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Malformed signed S3 chunk header");
                }
                return line.toString(StandardCharsets.US_ASCII);
            }
            line.write(current);
        }
    }
}
