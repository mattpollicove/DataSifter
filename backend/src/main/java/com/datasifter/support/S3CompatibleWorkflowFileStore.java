package com.datasifter.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3CompatibleWorkflowFileStore implements WorkflowFileStore {
    private static final long MAX_OBJECT_BYTES = 100L * 1024L * 1024L;
    private static final Pattern REFERENCE_KEY = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(csv|bin)");

    private final S3Client client;
    private final String bucket;

    public S3CompatibleWorkflowFileStore() {
        this(System.getenv());
    }

    S3CompatibleWorkflowFileStore(Map<String, String> environment) {
        String endpoint = ProviderSettings.required(environment, "DATASIFTER_STORAGE_S3_ENDPOINT");
        URI endpointUri = validateEndpoint(
                endpoint,
                ProviderSettings.enabled(environment, "DATASIFTER_STORAGE_S3_ALLOW_HTTP_LOCAL"));
        this.bucket = validateBucket(ProviderSettings.required(environment, "DATASIFTER_STORAGE_S3_BUCKET"));
        String region = ProviderSettings.optional(environment, "DATASIFTER_STORAGE_S3_REGION", "us-east-1");
        String accessKey = ProviderSettings.required(environment, "DATASIFTER_STORAGE_S3_ACCESS_KEY");
        String secretKey = ProviderSettings.required(environment, "DATASIFTER_STORAGE_S3_SECRET_KEY");
        S3Client configuredClient = S3Client.builder()
                .endpointOverride(endpointUri)
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        this.client = configuredClient;
        try {
            verifyBucket();
        } catch (RuntimeException e) {
            configuredClient.close();
            throw e;
        }
    }

    S3CompatibleWorkflowFileStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = validateBucket(bucket);
    }

    @Override
    public String providerId() {
        return "s3-compatible";
    }

    @Override
    public String store(InputStream content, String fileName) throws IOException {
        throw new IOException("S3 storage requires the upload content length");
    }

    @Override
    public String store(InputStream content, String fileName, long contentLength) throws IOException {
        if (content == null || contentLength < 1 || contentLength > MAX_OBJECT_BYTES) {
            throw new IOException("S3 uploads must be between 1 byte and 100 MiB");
        }
        String extension = fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".csv") ? ".csv" : ".bin";
        String key = UUID.randomUUID() + extension;
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromInputStream(content, contentLength));
            return "s3:" + key;
        } catch (RuntimeException e) {
            throw new IOException("Unable to store workflow input in S3-compatible storage", e);
        }
    }

    @Override
    public InputStream open(String reference) throws IOException {
        String key = keyFromReference(reference);
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return response;
        } catch (RuntimeException e) {
            throw new IOException("Unable to open workflow input from S3-compatible storage", e);
        }
    }

    @Override
    public void delete(String reference) throws IOException {
        String key = keyFromReference(reference);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException e) {
            throw new IOException("Unable to delete workflow input from S3-compatible storage", e);
        }
    }

    private void verifyBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            throw new IllegalStateException("Configured S3-compatible bucket is unavailable", e);
        }
    }

    private static String keyFromReference(String reference) throws IOException {
        if (reference == null || !reference.startsWith("s3:")) {
            throw new IOException("Workflow input reference does not belong to S3-compatible storage");
        }
        String key = reference.substring("s3:".length());
        if (!REFERENCE_KEY.matcher(key).matches()) {
            throw new IOException("Workflow input reference is invalid");
        }
        return key;
    }

    private static String validateBucket(String bucket) {
        if (bucket == null
                || bucket.length() < 3
                || bucket.length() > 63
                || !bucket.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])")
                || bucket.contains("..")
                || bucket.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            throw new IllegalStateException("S3-compatible bucket name is invalid");
        }
        return bucket;
    }

    private static URI validateEndpoint(String endpoint, boolean allowLocalHttp) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("S3-compatible endpoint is invalid", e);
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
                || uri.getRawFragment() != null) {
            throw new IllegalStateException("S3-compatible storage requires a valid HTTPS endpoint");
        }
        return uri;
    }
}
