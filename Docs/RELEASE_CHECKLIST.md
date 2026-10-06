# Cross-platform release checklist

## Automated gates

- Require the `Validate` job to pass on Ubuntu, Windows, and macOS before merging to `main`.
- The job compiles and tests the Java 25 backend, then installs the locked frontend dependencies and runs lint and production build checks.
- A `v*` tag assembles the backend JAR and frontend `dist` directory only after all three platform validations pass.

## Configuration and deployment smoke checks

- Set `DATASIFTER_SECURITY_ADMIN_USERNAME`, `DATASIFTER_SECURITY_ADMIN_PASSWORD`, and `DATASIFTER_WORKER_TOKEN` in the deployment secret store. Use distinct high-entropy credentials; never commit `.env` files.
- Install the chosen `VaultKeyProvider` and `WorkflowFileStore` plugin JARs under `DATASIFTER_PLUGIN_DIRECTORY`, register each with Java `ServiceLoader`, set their provider IDs, and verify startup with `DATASIFTER_VAULT_REQUIRE_MANAGED_PROVIDER=true` and `DATASIFTER_STORAGE_REQUIRE_DISTRIBUTED_PROVIDER=true`. No cloud provider is bundled.
- For upgrades from the local PBKDF2 vault, provide the old `DATASIFTER_VAULT_PASSPHRASE` during controlled migration; retain backups and legacy key material until all secrets are readable through the managed provider.
- Configure `DATASIFTER_DB_URL`, `DATASIFTER_DB_USERNAME`, and `DATASIFTER_DB_PASSWORD` for MySQL; limit database network access to API and worker services and grant Flyway migration permissions.
- Back up MySQL and the configured workflow-file store, then verify a restore before release. The Docker named volume is single-host storage, not suitable for multi-host worker deployments.
- Follow [Docs/OPERATIONS.md](./OPERATIONS.md) for the documented backup/restore and key-rotation procedure; confirm the provider migration path before changing storage or vault providers.
- Deploy independent frontend, API, and worker containers from `deploy/docker-compose.yml`. Keep the MySQL, API, frontend, and worker ports private; publish only Caddy's 80/443 ingress. Point `DATASIFTER_DOMAIN` to the deployment and verify automatic HTTPS and certificate renewal.
- Confirm the selected orchestrator's worker replica count and shutdown behavior, verify lease expiry/retry during forced worker termination, and ensure all replicas load identical provider plugins and configuration.
- Rotate provider-managed keys through the installed provider, preserving decrypt access to old key references until secret migration/retention policy allows retirement. The local `POST /api/keyvault/rotate` endpoint applies only to the local passphrase provider.
- Start the service and check unauthenticated `GET /actuator/health` and authenticated `GET /api/session`.
- Confirm an unauthenticated control-plane API request returns `401`, a viewer cannot mutate workflows or access the vault, and an operator can save workflows and upload CSV files.
- Confirm MySQL/PostgreSQL source and target connectivity with `sslMode=VERIFY_IDENTITY` / `sslmode=verify-full`, target table readiness, secret references, and correct JDBC driver availability.
- Confirm LDAPS chain validation against the JVM trust store, paged search behavior, base DN/filter limits, and least-privilege bind credentials.
- Verify CSV uploads are visible to all worker replicas through the configured shared storage provider and that failure/retry paths do not leak credentials.
- Verify worker token access separately from human HTTP Basic credentials; worker containers do not expose public HTTP ports.
- Run a representative job for every connector path and each privacy action, including decrypt/readback verification for field-level encryption.
- Repeat startup, sign-in, upload, job completion, secret read, backup/restore, and shutdown checks on supported CI platforms before publishing.
