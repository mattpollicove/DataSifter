# Provider adapter contracts

DataSifter keeps cloud services behind two Java Service Provider Interface (SPI) contracts. The repository ships local development implementations only; it does not ship or claim a production KMS or object-storage integration.

## Managed vault keys

Implement `com.datasifter.support.VaultKeyProvider` and include the implementation JAR in `DATASIFTER_PLUGIN_DIRECTORY`. Register the implementation class in:

```text
META-INF/services/com.datasifter.support.VaultKeyProvider
```

The provider must:

- Return a stable, unique provider ID and the active managed-key reference.
- Wrap and unwrap 256-bit per-secret data-encryption keys using authenticated key wrapping backed by the external KMS/HSM.
- Resolve historical key references for as long as stored ciphertext needs them. The application encrypts new secrets with the active key reference and records the reference with the wrapped key.
- Fail on authorization, network, key-version, or integrity errors; never return plaintext or a success-shaped fallback.
- Never log key material, wrapped keys, plaintext secrets, or authentication tokens.

Configure `DATASIFTER_VAULT_PROVIDER` to the provider ID. Production deployments set `DATASIFTER_VAULT_REQUIRE_MANAGED_PROVIDER=true`; the application then refuses to start with the local PBKDF2 provider or an unavailable plugin. Provider-side rotation must keep old key versions decryptable until the provider's data migration and retention policy permit retirement. Local `POST /api/keyvault/rotate` is not a managed-provider rotation API.

The versioned `v3` payload uses AES-256-GCM with a fresh data key per secret and authenticates the provider and key reference as associated data. Existing local `v2` values can be migrated when their local passphrase is supplied during controlled startup migration.

## Shared workflow storage

Implement `com.datasifter.support.WorkflowFileStore` and register it in:

```text
META-INF/services/com.datasifter.support.WorkflowFileStore
```

The provider must:

- Return a stable provider ID matching `DATASIFTER_STORAGE_PROVIDER`.
- Store the input stream durably and return an opaque reference safe for persistence in a workflow.
- Stream the requested object from `open(reference)` without loading it entirely into heap.
- Remove a newly uploaded object through `delete(reference)` when workflow persistence fails.
- Prevent cross-tenant or cross-workflow reference access in provider policy, and fail closed for missing objects or authorization failures.
- Apply encryption at rest, retention, backup, and access logging according to deployment policy.

The upload controller owns and closes the input stream passed to `store`; the consumer owns and must close the stream returned by `open`. Upload references must not contain credentials or secret material. Configure `DATASIFTER_STORAGE_REQUIRE_DISTRIBUTED_PROVIDER=true` to reject the built-in local filesystem provider. The local adapter and Docker named volume are only for development or one Docker host.

Switching existing workflows from `local:` references to another provider is not automatic. Copy all referenced objects and update workflow references as a controlled migration; retain the old store and backups until verified.

## Building and installing plugins

Compile provider implementations against the backend's SPI types and the provider vendor's supported client SDK. Package provider libraries in the plugin JAR as required by `ServiceLoader`, mount their directory read-only at `/opt/datasifter/plugins` in both API and worker containers, and set provider IDs in deployment configuration. Test provider identity, permission denial, transient network failures, key rotation with old ciphertext, object streaming, and backup restoration before promoting a release.

The SPI is a deployment extension point, not a complete vendor integration. Production remains blocked until actual provider plugins are selected, installed, and validated.

## Connector transport requirements

- LDAP sources must use `ldaps://`, reject URL user-info, require the JVM trust store to validate the server certificate, and use paging. Install the directory CA chain through the deployment's standard trust-store provisioning process; do not add trust-all managers.
- MySQL data-plane JDBC URLs must set `sslMode=VERIFY_IDENTITY`; PostgreSQL URLs must set `sslmode=verify-full`. Processing adds bounded connection/socket timeouts. Configure least-privilege source `SELECT` and target `INSERT` users separately and restrict network egress to approved connector hosts.
- H2 in-memory JDBC URLs are allowed for automated tests only.
