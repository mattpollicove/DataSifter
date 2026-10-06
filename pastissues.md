# DataSifter Past Issues

## 2026-10-06 | Incomplete application identity rename

- Description: The initial DataSifter rename changed several top-level names but left legacy Spring property prefixes, deployment environment-variable names, a custom drag-and-drop MIME type, and the generated backend JAR glob inconsistent.
- Root cause: Branding replacements were applied only to files containing the full prior product name, while some configuration files contained only the shorter legacy namespace.
- Solution: Aligned Spring property keys, test properties, deployment variables, Caddy configuration, Docker volume and JAR names, CI release artifacts, and frontend drag-and-drop identifiers. Added a migration guide documenting old-to-new configuration and preserving existing database and filesystem state.
- Affected files:
  - `backend/src/main/resources/application.properties`
  - `backend/src/main/java/com/datasifter/config/`
  - `backend/src/test/`
  - `backend/pom.xml`
  - `deploy/`
  - `frontend/src/App.jsx`
  - `Docs/RENAMING_TO_DATASIFTER.md`
- Prevention notes:
  - Audit identity changes by searching for legacy namespaces and prefixes, not only full product names.
  - Coordinate runtime properties, deployment variables, test fixtures, packaging globs, and migration instructions before validating a rename.

## 2026-10-06 | Control-plane repository/service wiring and JSON persistence mismatch

- Description: The first pass of the control-plane state layer introduced repository and service classes, but the MVC test slice was still missing the required Spring beans. This caused the controller tests to fail during application context startup.
- Root cause: The repository and service wiring existed in the project, but the test configuration was not importing the real beans used by the controller stack. A companion issue in the JSON persistence layer also emerged because typed list reads were being initialized without explicit generic targets.
- Solution: The test configuration was updated to include the service/repository beans, and the JSON file helper was hardened to load typed lists with explicit `TypeReference` usage and portable `Path` handling.
- Affected files:
  - `backend/src/main/java/com/datasifter/controller/DashboardController.java`
  - `backend/src/main/java/com/datasifter/support/JsonFileStore.java`
  - `backend/src/test/java/com/datasifter/DashboardControllerTest.java`
- Prevention notes:
  - Always validate controller wiring against the real Spring context when introducing new service/repository dependencies.
  - Use explicit generic `TypeReference` reads for persisted JSON arrays and keep file operations OS-neutral.

## 2026-10-06 | Cross-platform assumptions in persistence and runtime behavior

- Description: The project initially included assumptions about local environment specifics and sample state that could break outside the developer workstation.
- Root cause: Hard-coded assumptions and environment-specific behavior were mixed into the early control-plane prototype.
- Solution: The project now uses portable Java `Path` and `Files` APIs, user-home state under `.datasifter`, and explicit cross-platform compatibility rules captured in `RULES.md`.
- Affected files:
  - `RULES.md`
  - `backend/src/main/java/com/datasifter/support/JsonFileStore.java`
- Prevention notes:
  - Prefer OS-neutral APIs and portable file layouts.
  - Review all new behavior against Linux, macOS, and Windows compatibility before considering a change complete.

## 2026-10-06 | Secret handling discipline

- Description: The control plane was exposing secret values in a way that could be unsafe in client responses if not masked or validated.
- Root cause: Secret storage and display logic were not yet enforcing a minimum-safe validation standard for value and metadata fields.
- Solution: Secret creation now validates required metadata and trims input values; response masking remains in place, and the service rejects blank or null secret values.
- Affected files:
  - `backend/src/main/java/com/datasifter/service/KeyVaultService.java`
  - `backend/src/main/java/com/datasifter/controller/DashboardController.java`
- Prevention notes:
  - Secrets must always be validated before persistence and masked before client display.
  - Avoid a “just store it and worry later” approach for sensitive state.

## 2026-10-06 | Stale mock dashboard state reintroduced in the UI

- Description: The dashboard and workflow detail views still included static sample jobs, alerts, and workflow names that were inconsistent with the real control-plane state and could mislead operators.
- Root cause: The frontend fallback path and backend dashboard summary both included hard-coded example values instead of using actual service-backed results when the data set was empty.
- Solution: The fallback state was replaced with neutral empty-state values, the dashboard summary uses service data and safe zero-state metrics, and workflow details no longer default to the old example identity provisioning workflow.
- Affected files:
  - `backend/src/main/java/com/datasifter/controller/DashboardController.java`
  - `frontend/src/App.jsx`
  - `README.md`
- Prevention notes:
  - Treat static examples as non-production placeholders and remove them from default runtime paths.
  - Validate empty-state behavior against the real control-plane API instead of assuming sample data is present.

## 2026-10-06 | JSON persistence could hide corruption and truncate state on writes

- Description: A JSON store read failure returned an empty collection, while writing directly to the target file could leave existing state truncated if the process or filesystem failed mid-write.
- Root cause: The shared JSON helper treated all read IO/parse errors as a missing store and overwrote the destination in place without file-level synchronization.
- Solution: Missing files alone now initialize as empty; malformed or unreadable existing files fail explicitly. Writes serialize to a temporary file, flush it, and replace the target atomically when supported, with a portable replacement fallback. Per-file in-process read/write locks and synchronized repository mutations protect local state updates.
- Affected files:
  - `backend/src/main/java/com/datasifter/support/JsonFileStore.java`
  - `backend/src/main/java/com/datasifter/repository/`
  - `backend/src/test/java/com/datasifter/support/JsonFileStoreTest.java`
  - `backend/src/test/java/com/datasifter/repository/JsonRepositoryPersistenceTest.java`
- Prevention notes:
  - Do not interpret parse, permission, or IO failures as first-run empty state.
  - Use replacement writes and explicit repository restart tests for persistent state.
  - The local JSON store is not a multi-process transactional database; migrate to a database before multi-instance production deployment.
  - Windows may transiently deny a replacement move while another process inspects the target; replacement now retries that specific error with bounded backoff before failing explicitly.

## 2026-10-06 | Transient Maven test-compilation failure during persistence validation

- Description: One full test invocation failed in `testCompile` with unresolved references to production packages, despite production classes being present in `target/classes`.
- Root cause: The precise trigger was not established; the failure was transient and consistent with an inconsistent Maven incremental compiler state.
- Solution: Running the production compile phase and then rerunning the complete test suite succeeded without source changes related to the compiler error.
- Affected files:
  - `backend/src/test/java/com/datasifter/DashboardControllerTest.java`
  - `backend/src/test/java/com/datasifter/repository/JsonRepositoryPersistenceTest.java`
  - `backend/src/test/java/com/datasifter/support/JsonFileStoreTest.java`
- Prevention notes:
  - If Maven reports project classes missing, verify main compilation and rerun the standard test command before attributing the failure to source changes.
  - The first invocation failed; the subsequent complete backend test run passed.

## 2026-10-06 | Database migration test exposed payload and identifier assumptions

- Description: Initial MySQL-compatible repository tests exposed two test-design incompatibilities: prefixed test IDs exceeded the UUID-sized database key field, and H2's JSON column treated serialized JSON as a JSON string scalar.
- Root cause: The schema is designed for application-generated UUID identifiers, while H2's JSON binding behavior differs from the desired transparent string payload handling.
- Solution: Tests now use UUID-length keys, and repository payloads are stored as `LONGTEXT` serialized JSON so the same text is read back by MySQL and H2.
- Affected files:
  - `backend/src/main/resources/db/migration/V1__create_control_plane_tables.sql`
  - `backend/src/main/java/com/datasifter/repository/JdbcJsonTable.java`
  - `backend/src/test/java/com/datasifter/repository/JdbcRepositoryPersistenceTest.java`
- Prevention notes:
  - Validate SQL storage types against both the production database and the compatibility test database.
  - Keep identifiers within the supported 36-character UUID format; test IDs should match the production identifier contract.

## 2026-10-06 | Worker lease migration and expiry-boundary validation

- Description: The initial queue migration's multi-column `ALTER TABLE` syntax was rejected by H2 MySQL mode, and the first lease-recovery assertion at the exact expiry instant was sensitive to timestamp precision.
- Root cause: H2 requires separate column-add operations for this migration shape; cross-database timestamp precision makes equality-boundary assertions less robust than the scheduled recovery poll interval.
- Solution: Added queue columns with individual `ALTER TABLE` statements and microsecond timestamp precision. Lease recovery tests now advance past the expiry instant, matching the periodic reaper behavior.
- Affected files:
  - `backend/src/main/resources/db/migration/V2__add_job_queue_leases.sql`
  - `backend/src/main/java/com/datasifter/repository/JdbcJobExecutionRepository.java`
  - `backend/src/test/java/com/datasifter/repository/JdbcRepositoryPersistenceTest.java`
- Prevention notes:
  - Run every Flyway migration under the production dialect and the configured compatibility test database.
  - Test timed lease expiration after the deadline rather than assuming exact cross-database timestamp equality.

## 2026-10-06 | Control plane lacked enforced identity, vault key rotation, and executable data flow

- Description: API role checks were not connected to request authentication, the vault master key was stored beside ciphertext, workflows did not persist the canvas/mapping model, and CSV uploads carried metadata rather than content.
- Root cause: The initial implementation established CRUD screens and a durable queue protocol before adding an end-to-end authenticated execution path.
- Solution: Added environment-configured HTTP Basic users with role-specific API authorization and CSRF tokens; versioned AES-GCM vault encryption; typed persisted workflows; multipart upload; and a separate worker runtime for streaming CSV/JDBC/LDAPS processing. Added cloud-neutral key and file-storage SPIs, production startup gates, container deployment, and CI/release checks.
- Affected files:
  - `backend/src/main/java/com/datasifter/config/SecurityConfig.java`
  - `backend/src/main/java/com/datasifter/support/VaultCrypto.java`
  - `backend/src/main/java/com/datasifter/service/CsvJdbcJobProcessor.java`
  - `backend/src/main/java/com/datasifter/service/WorkerRuntime.java`
  - `backend/src/main/java/com/datasifter/support/VaultKeyProvider.java`
  - `backend/src/main/java/com/datasifter/support/WorkflowFileStore.java`
  - `backend/src/main/java/com/datasifter/model/Workflow.java`
  - `frontend/src/App.jsx`
  - `.github/workflows/ci.yml`
- Prevention notes:
  - Keep human credentials and the worker shared token separate; use HTTPS outside localhost and configure the required secrets in the deployment secret store.
  - Preserve legacy `vault-master.key` backups until legacy state has been imported and verified; after passphrase rotation, update the environment before restarting.
  - Pre-create and validate JDBC target tables. Queue delivery is at least once, so target data should enforce suitable unique keys or deduplication.
  - Install and validate concrete KMS and shared object-storage providers before production; no vendor adapter is bundled.
  - Tenant isolation, broader v6 processing modes, and actual CI/release results remain open.

## 2026-10-06 | Production worker needed independent storage and managed-key boundaries

- Description: The initial worker ran inside the API process, uploads used node-local file paths, and production vault data depended on a local passphrase.
- Root cause: The MVP prioritized a functioning CSV-to-JDBC path before defining external worker deployment and provider integrations.
- Solution: Added separate worker container execution, opaque file references via a `WorkflowFileStore` SPI, managed envelope encryption via a `VaultKeyProvider` SPI, production fail-closed provider configuration, CSV/JDBC/LDAPS sources, JDBC TLS enforcement, bounded streaming, and deployment documentation.
- Affected files:
  - `backend/src/main/java/com/datasifter/service/WorkerRuntime.java`
  - `backend/src/main/java/com/datasifter/service/CsvJdbcJobProcessor.java`
  - `backend/src/main/java/com/datasifter/support/WorkflowFileStore.java`
  - `backend/src/main/java/com/datasifter/support/VaultKeyProvider.java`
  - `deploy/docker-compose.yml`
  - `Docs/ADAPTERS.md`
- Prevention notes:
  - Install real managed KMS and shared object-storage plugins and validate their authorization, rotation, outage, and restore behavior before production.
  - Run multi-host workers only with durable shared storage and a supported scheduler; the local named volume is single-host only.
  - Exercise live JDBC and LDAPS endpoints with trusted certificate chains and least-privilege connector credentials before release.

## 2026-10-06 | Final worker integration compile checks

- Description: The first Java compile after separating the worker and adding provider selection caught a misplaced method declaration and a missing `Path` import.
- Root cause: New cross-cutting changes were integrated before the first clean compile.
- Solution: Moved the managed-provider status accessor to class scope, restored the required import, and reran the full clean Maven verification after fixing the issues.
- Affected files:
  - `backend/src/main/java/com/datasifter/support/VaultCrypto.java`
  - `backend/src/main/java/com/datasifter/controller/WorkflowCsvController.java`
- Prevention notes:
  - Keep a clean compile as the first verification gate after multi-file wiring changes; final verification passed all backend tests.
