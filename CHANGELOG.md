# DataSifter Changelog

## Unreleased

### Added
- Added built-in HashiCorp Vault Transit envelope-key wrapping and S3-compatible workflow-file storage providers, configured for production Compose with Docker secret files.
- Added provider configuration checks requiring verified HTTPS endpoints, least-privilege credentials, bucket readiness, and bounded-length streaming uploads.
- Added environment-configured role-based HTTP Basic authentication, CSRF protection for browser mutations, and administrator/auditor access boundaries.
- Added PBKDF2-derived versioned AES-GCM vault encryption, strict decryption errors, legacy ciphertext migration, and explicit passphrase rotation.
- Added persisted CSV/JDBC/LDAPS source configuration, JDBC target configuration, and field transformations for trim/lowercase/uppercase.
- Added standalone worker execution from the same artifact, target support for MySQL/PostgreSQL, heap-aware bounded batches, paged LDAP reads, and field-level vault encryption.
- Enforced certificate/hostname-verified TLS for MySQL/PostgreSQL connector URLs and validated JDBC batch results before committing target transactions.
- Added `WorkflowFileStore` and `VaultKeyProvider` ServiceLoader extension points; managed envelope encryption uses versioned per-secret data keys.
- Added a production container stack with separate frontend/API/worker services, private backend/database ports, HTTPS ingress, shared-storage enforcement, and secret-provider enforcement.
- Added production fail-closed settings requiring managed key and shared storage providers, plus a Docker Compose stack with isolated API/worker/MySQL and Caddy HTTPS ingress.
- Added storage path-traversal checks and tests for opaque local upload references, JDBC-source processing, managed key-provider encryption, and LDAP TLS policy.
- Updated shell and PowerShell file agents to upload file contents to a selected workflow.
- Added Linux, Windows, and macOS CI validation, tag-gated release artifact packaging, and a cross-platform smoke checklist.
- Extended the Linux integration smoke to exercise Vault Transit with a scoped token and S3-compatible CSV upload/readback against ephemeral services.
- Added security, vault rotation, workflow persistence, and CSV-to-JDBC processing test coverage.
- Added a shared-token-authenticated worker API with durable database-backed job claims, heartbeats, completion/failure reporting, retry backoff, and expired-lease recovery.
- Added configurable job retry and worker lease policies with defaults of three retries and a 60-second lease.
- Added default MySQL-backed repositories for workflows, connectors, jobs, audit events, and encrypted key-vault records, with Flyway-managed initial schema.
- Added an opt-in, transactional, one-time importer for existing local JSON state into an empty MySQL database.
- Added H2 MySQL-mode test configuration and database repository integration coverage.
- Kept repository payload columns as `LONGTEXT` JSON documents so serialized Java values round-trip identically in MySQL and the H2 MySQL-mode test database.
- Added focused JSON-store and repository persistence tests covering malformed files, write failures, replacement writes, concurrent updates, and state recovery across repository instances.
- Added a project-level engineering rules document at `RULES.md` and aligned it to the actual Java/Spring Boot + React project structure.
- Added issue tracking, prompt logging, and migration documentation requirements to the repo-level workflow.
- Added a workflow/job state path and real control-plane state model for workflows, connectors, job executions, audit entries, and key-vault secrets.
- Added masked secret responses and stricter validation in the secret service.
- Added an encrypted-at-rest vault layer for key values using Java AES/GCM and a generated per-installation master key stored in the user data directory.
- Removed remaining mock dashboard fallback data so the control-plane shows empty, neutral state instead of stale example jobs, alerts, and workflow details.
- Removed the unused hard-coded demo-secret helper from the key-vault repository.

### Changed
- Renamed the application identity, Java packages, runtime configuration, deployment variables, container artifacts, and documentation from NexusSync to DataSifter; added a non-destructive migration guide.
- Pinned Flyway 11.20.3 instead of Spring Boot's older managed Flyway version to align migration tooling with the MySQL 8.4 production and CI target.
- The control-plane UI now requires sign-in, keeps Basic credentials in memory, hides role-restricted screens, and persists workflow canvas/mapping changes through the API.
- Vault startup now requires a configured passphrase; legacy key material remains available only to decrypt pre-migration ciphertext and must be backed up through the documented transition.
- Configured Maven Surefire to load Mockito as a Java agent for Java 25 test runs, avoiding Mockito's dynamic self-attachment.
- Upgraded the backend runtime to Java 25 LTS and Spring Boot 3.5.16.
- The default application profile now uses MySQL persistence; the portable local JSON repositories are retained behind the explicit `json` Spring profile.
- JSON persistence now reports unreadable or malformed existing data instead of silently treating it as empty, and writes use per-file in-process locks plus temporary-file replacement with an atomic-move fallback.
- Repository constructors now accept isolated file paths for tests, and repository mutation methods serialize in-process updates before persisting snapshots.
- Updated the control plane to use service/repository-backed state instead of mock-only placeholder data for workflows, connectors, audits, and job executions.
- Kept persistence portable by using Java `Path`/`Files` APIs and user-home state under `.datasifter` rather than Windows-specific paths.
- Tightened project rules to require explicit user confirmation before builds, best-practice implementation standards, change summaries for milestone work, and ongoing documentation updates.
- Added pause/resume/cancel lifecycle support for queued workflow jobs and normalized job statuses to keep execution state consistent across the control plane.
- Added operational health and queue summary endpoints to support phase-1 readiness planning and release gating.
- Added a phased roadmap in `Docs/PHASED_ROADMAP.md` with Phase 1/2/3 milestones.

### Fixed
- Added bounded retries for transient Windows access-denied errors when atomically replacing a JSON state file under concurrent writes.
- Prevented interrupted JSON writes from truncating the prior store file, and stopped state corruption from being masked as a successful empty-store startup.
- Resolved the missing service/repository wiring issue in the controller test setup.
- Updated tests to validate the current real control-plane state without assuming stale sample-job data.
- Removed the remaining hard-coded mock seed data from the workflow, connector, audit, and key-vault repositories so first-run state is empty and actual user-created records persist in the local JSON store.
- Added missing project documentation artifacts: `pastissues.md`, `PROMPT_LOG.md`, and `Docs/APP_CHANGES_FOR_MIGRATION.md`.

### Notes
- Production provider adapters target HashiCorp Vault Transit and an S3-compatible object store (such as MinIO). The external Vault and S3 services, keys, bucket policies, TLS trust chains, and staging recovery validation must still be provisioned and verified by the deployment owner.
- Phase 1 processing now covers CSV, JDBC, and LDAPS into JDBC. SaaS/API connectors, rate limiting, scripting, success/failure gates, 1:many expansion, many:1 aggregation, nested JSON mapping, conflict resolution, and AI-assisted configuration remain future work.
- All changes remain written to support Linux, macOS, and Windows portability, consistent with the project rules.
